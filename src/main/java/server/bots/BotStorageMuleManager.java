package server.bots;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapItem;
import tools.DatabaseConnection;

import java.awt.Point;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntPredicate;

/**
 * Storage mule visits, the way a player hands loot to a storage character. When a farmer's ETC tab is cramped
 * with maker materials it can't craft with (the {@link BotInventoryManager#etcShelf}), it walks into the Free
 * Market; the roster's storage character for each material wakes up there; the farmer drops that bin's stacks
 * a batch at a time beside it and the mule picks them up (the normal passive loot, {@link Character#pickupItem});
 * then the mule logs out and the farmer goes back to work. Design: docs/bot/etc-storage-mules.md.
 *
 * <p>Order matters: the mule stands there before anything hits the floor. Dropped items start to vanish after
 * 3 minutes, and any bot may loot another bot's drop after 15 s; the designated mule is exempt from that wait
 * ({@link #isPartnerDrop}), so a batch is gone in a few seconds. The shelf stays the backstop: whatever no bin
 * takes, or a visit that fails, sells on the next cramped shop trip.
 */
final class BotStorageMuleManager {
    private static final Logger log = LoggerFactory.getLogger(BotStorageMuleManager.class);

    private BotStorageMuleManager() {}

    // ---- bins: the roster's storage roles (docs/bot/rosters/sipsbuddy.csv, tier storage) -------------------

    record Bin(String role, IntPredicate takes) {}

    private static boolean in(int id, int first, int last) {
        return id >= first && id <= last;
    }

    /** Jewel ores 40200xx and jewels 40210xx by gem index (0 Garnet, 1 Amethyst, 2 AquaMarine, 3 Emerald,
     *  4 Opal, 5 Sapphire, 6 Topaz, 7 Diamond, 8 Black Crystal). */
    private static boolean gem(int id, int... indexes) {
        for (int i : indexes) {
            if (id == 4020000 + i || id == 4021000 + i) {
                return true;
            }
        }
        return false;
    }

    // First match wins; Processed materials is the catch all for what the named bins don't take.
    static final List<Bin> BINS = List.of(
            new Bin("Power crystals", id -> in(id, 4004000, 4004003) || in(id, 4005000, 4005003)),
            new Bin("Dark crystals", id -> id == 4004004 || id == 4005004),
            new Bin("Diamond, sapphire, aquamarine", id -> gem(id, 7, 5, 2)),
            new Bin("Emerald, topaz, opal", id -> gem(id, 3, 6, 4)),
            new Bin("Garnet, amethyst, black crystal", id -> gem(id, 0, 1, 8)),
            new Bin("Mithril, adamantium, steel", id -> in(id, 4010001, 4010003) || in(id, 4011001, 4011003)),
            // No role names silver, orihalcon, gold or lidium; the metals bin holds them until one does.
            new Bin("Iron and bronze", id -> id == 4010000 || id == 4011000 || in(id, 4010004, 4010007)
                    || in(id, 4011004, 4011006) || id == 4011008),
            // Powders, stimulators and manuals, monster crystals, maker reagents, Moon Rock, Piece of Time, Star Rock.
            new Bin("Processed materials", id -> id / 1000 == 4007 || id / 10000 == 413 || id / 10000 == 426
                    || ItemConstants.isMakerReagent(id) || id == 4011007 || id == 4020009 || id == 4021009));

    /** The storage role that takes this item, or null. */
    static String binRole(int itemId) {
        for (Bin bin : BINS) {
            if (bin.takes().test(itemId)) {
                return bin.role();
            }
        }
        return null;
    }

    private static String roleKey(String role) {
        return role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
    }

    // ---- directory: which roster character holds which bin, per registered owner ------------------------------
    // Loaded off the tick thread (a DB scan), refreshed every 30 minutes. Until it is ready, visits don't start.

    record Directory(Map<Integer, Integer> ownerOf, Map<Integer, Map<String, Integer>> mulesByOwner,
                     Map<Integer, String> roleOfMule, long loadedAtMs) {
        Integer muleFor(int farmerId, String role) {
            Integer owner = ownerOf.get(farmerId);
            Map<String, Integer> mules = owner == null ? null : mulesByOwner.get(owner);
            return mules == null ? null : mules.get(roleKey(role));
        }
    }

    private static final long DIRECTORY_TTL_MS = 30 * 60_000L;
    private static volatile Directory directory;
    private static final AtomicBoolean loading = new AtomicBoolean();

    /** Test seam: the directory loader (a DB scan of bot_config joined with bot_owners). */
    interface DirectoryLoader {
        Directory load(long nowMs) throws Exception;
    }

    static DirectoryLoader directoryLoader = BotStorageMuleManager::loadDirectory;

    static Directory directory(long nowMs) {
        Directory d = directory;
        if ((d == null || nowMs - d.loadedAtMs() > DIRECTORY_TTL_MS) && loading.compareAndSet(false, true)) {
            Thread.ofPlatform().daemon().name("bot-storage-mules").start(() -> {
                try {
                    directory = directoryLoader.load(System.currentTimeMillis());
                } catch (Exception e) {
                    log.warn("storage mules: could not load the roster directory", e);
                } finally {
                    loading.set(false);
                }
            });
        }
        return d;
    }

    static void setDirectoryForTest(Directory d) {
        directory = d;
    }

    private static Directory loadDirectory(long nowMs) throws Exception {
        Map<String, Bin> binByRole = new HashMap<>();
        for (Bin bin : BINS) {
            binByRole.put(roleKey(bin.role()), bin);
        }
        Map<Integer, Integer> ownerOf = new HashMap<>();
        Map<Integer, Map<String, Integer>> mulesByOwner = new HashMap<>();
        Map<Integer, String> roleOfMule = new HashMap<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT b.bot_char_id, b.config, o.owner_char_id FROM bot_config b "
                             + "JOIN bot_owners o ON o.bot_char_id=b.bot_char_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int bot = rs.getInt("bot_char_id");
                int owner = rs.getInt("owner_char_id");
                ownerOf.put(bot, owner);
                String role = roleKey(BotPersonality.parse(rs.getString("config")).rosterRole());
                if (binByRole.containsKey(role)) {
                    mulesByOwner.computeIfAbsent(owner, k -> new HashMap<>()).put(role, bot);
                    roleOfMule.put(bot, role);
                }
            }
        }
        log.info("storage mules: {} bins across {} owners", roleOfMule.size(), mulesByOwner.size());
        return new Directory(ownerOf, mulesByOwner, roleOfMule, nowMs);
    }

    // ---- mule duty (mule side) -------------------------------------------------------------------------------

    record Duty(int partnerId, long sinceMs) {}

    // muleCharId -> who it waits for. Written when a visit summons it (any tick thread), read by its own tick.
    static final Map<Integer, Duty> DUTIES = new ConcurrentHashMap<>();
    private static final long DUTY_MAX_MS = 30 * 60_000L; // eight bins served one after another fit in this

    /** Test seam: bring an offline mule online (a DB load, so never on a tick thread). */
    interface Summoner {
        void summon(int muleCharId);
    }

    // One login at a time: a character load holds several pool connections, and eight at once exhausted the
    // pool (HikariPool timeout, loadCharFromDB returned null). Serial logins also stagger like players do.
    private static final ExecutorService SUMMONS = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("bot-mule-summon").factory());

    static Summoner summoner = muleId -> SUMMONS.execute(() -> {
        BotManager manager = BotManager.getInstance();
        boolean online;
        try {
            online = manager.spawnManagedBot(muleId) || manager.getEntryByBotCharId(muleId) != null;
        } catch (RuntimeException e) {
            log.warn("storage mules: could not summon {}", muleId, e);
            online = false;
        }
        if (!online) {
            DUTIES.remove(muleId); // the farmer skips this bin instead of waiting for it
        }
    });

    static boolean onDuty(BotEntry entry) {
        return entry != null && entry.bot != null && DUTIES.containsKey(entry.bot.getId());
    }

    /** A drop the mule on duty for {@code partnerId} may take at once (no 15 s wait for bot drops). */
    static boolean isPartnerDrop(Character looter, MapItem drop) {
        if (looter == null || drop == null || !drop.isPlayerDrop()) {
            return false;
        }
        Duty duty = DUTIES.get(looter.getId());
        return duty != null && duty.partnerId() == drop.getOwnerId();
    }

    private static void endDuty(int muleId) {
        if (DUTIES.remove(muleId) == null) {
            return;
        }
        BotManager manager = BotManager.getInstance();
        BotEntry mule = manager.getEntryByBotCharId(muleId);
        if (mule != null && mule.bot != null && !mule.loggingOut) {
            manager.logoutManagedBot(muleId);
            mule.logoutLingerUntilMs = System.currentTimeMillis() + BotManager.randMs(5_000, 20_000);
        }
    }

    /**
     * The mule's tick while on duty: walk to the Free Market, then stand near the drops its partner leaves and
     * let passive loot take them. Runs after the map change rebuild and before the autopilot (a mule sits at
     * its training cap, so the autopilot has nothing for it). True when it consumed the tick.
     */
    static boolean tickMule(BotEntry entry, Character bot, boolean runAiTick) {
        Duty duty = DUTIES.get(bot.getId());
        if (duty == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        BotEntry partner = BotManager.getInstance().getEntryByBotCharId(duty.partnerId());
        if (now - duty.sinceMs() > DUTY_MAX_MS || partner == null || partner.bot == null
                || partner.muleVisitMapId == -1 || !partner.muleVisitBins.contains(bot.getId())) {
            endDuty(bot.getId());
            return false;
        }
        if (bot.getMapId() != BotWorldGraph.FREE_MARKET) {
            boolean moved = BotTravelManager.tickTravel(entry, bot, BotWorldGraph.FREE_MARKET,
                    BotAutopilotManager.MAX_TRAVEL_HOPS, runAiTick, false);
            if (!moved && entry.lastMapId == bot.getMapId()) {
                endDuty(bot.getId()); // no way to the market from here: the farmer skips this bin
            }
            return moved;
        }
        // Stand on the partner's newest drop (passive loot picks it up within its radius), else near the entry.
        Point target = null;
        for (MapItem drop : bot.getMap().getDroppedItems()) {
            if (isPartnerDrop(bot, drop) && BotLootEligibility.isPresent(bot.getMap(), drop)) {
                target = drop.getPosition();
                break;
            }
        }
        if (target == null) {
            if (entry.muleStandSpot == null) {
                entry.muleStandSpot = BotTravelManager.pickReachableApproachPoint(
                        entry, bot, marketMeetingPoint(bot), BotTravelManager.APPROACH_SPREAD_PX);
            }
            target = entry.muleStandSpot;
        }
        if (target != null && (entry.inAir || entry.climbing || manhattan(bot.getPosition(), target) > STAND_PX)) {
            BotTravelManager.pinMoveTarget(entry, target);
            BotTravelManager.movementStep.step(entry, target, runAiTick);
        }
        return true;
    }

    // Where mules wait and farmers look for them: the market's own exit portal, beside the entrance spawn.
    private static Point marketMeetingPoint(Character bot) {
        var exit = bot.getMap().getPortal(BotWorldGraph.FREE_MARKET_EXIT_PORTAL);
        return exit != null ? exit.getPosition() : bot.getPosition();
    }

    // ---- the visit (farmer side, a detour errand) ------------------------------------------------------------

    private static final long VISIT_CHECK_EVERY_MS = 30_000L;
    private static final long VISIT_STALL_MS = 6 * 60_000L;   // no hop progress for this long: give up
    private static final long MULE_WAIT_MS = 4 * 60_000L;     // at the market, wait this long for the next mule
    private static final long PICKUP_WAIT_MS = 30_000L;       // a dropped batch must be gone by then
    private static final int BATCH_STACKS = 8;
    private static final int STAND_PX = 40;
    private static final int HANDOFF_PX = 60;
    // A mule found with a full ETC tab is skipped for a while (no trip to the market for nothing); its
    // materials sell from the shelf meanwhile. Nothing empties a bin today, so the retry is a long one.
    private static final long FULL_BIN_RETRY_MS = 6 * 60 * 60_000L;
    private static final Map<Integer, Long> FULL_BINS = new ConcurrentHashMap<>();

    static boolean visitActive(BotEntry entry) {
        return entry != null && entry.muleVisitMapId != -1;
    }

    /**
     * The farmer's shelf grouped by the mule that takes it, in bin order: mule char id to the stacks it gets.
     * Items no bin takes, or whose bin has no mule for this owner, stay out (the shelf still sells them).
     */
    static Map<Integer, List<Item>> planBins(Directory dir, int farmerId, List<Item> shelf) {
        Map<Integer, List<Item>> plan = new LinkedHashMap<>();
        if (dir == null) {
            return plan;
        }
        for (Item item : shelf) {
            String role = binRole(item.getItemId());
            Integer mule = role == null ? null : dir.muleFor(farmerId, role);
            if (mule != null && mule != farmerId) {
                plan.computeIfAbsent(mule, k -> new ArrayList<>()).add(item);
            }
        }
        return plan;
    }

    /** The bins this farmer can visit now: {@link #planBins} minus mules someone else has (a player logged it
     *  in, or another farmer's visit holds it). */
    private static Map<Integer, List<Item>> availablePlan(Character bot, Directory dir) {
        Map<Integer, List<Item>> plan = planBins(dir, bot.getId(), BotInventoryManager.etcShelf(bot));
        long now = System.currentTimeMillis();
        plan.keySet().removeIf(mule -> {
            if (DUTIES.containsKey(mule) || now < FULL_BINS.getOrDefault(mule, 0L)) {
                return true;
            }
            BotEntry m = BotManager.getInstance().getEntryByBotCharId(mule);
            return m != null && m.bot != null && m.owner != m.bot;
        });
        return plan;
    }

    /**
     * True while the shelf's materials are spoken for by a mule: a visit is under way, or one is due (an
     * autopilot farmer off cooldown with stacks a free mule takes). The cramped shop trip then leaves the
     * shelf alone and sells only true trash. A visit that ends, well or not, starts the cooldown, so
     * whatever stayed behind sells on the next trip.
     */
    static boolean holdsShelf(BotEntry entry, Character bot) {
        if (visitActive(entry)) {
            return true;
        }
        if (entry == null || bot == null || entry.following || !BotAutopilotManager.isActive(entry)
                || System.currentTimeMillis() < entry.muleNextVisitAtMs) {
            return false;
        }
        Directory dir = directory(System.currentTimeMillis());
        return dir != null && !availablePlan(bot, dir).isEmpty();
    }

    /** Start a visit when the ETC tab is cramped and some shelf stacks have a mule to go to. */
    static void maybeStartVisit(BotEntry entry, Character bot) {
        long now = System.currentTimeMillis();
        if (visitActive(entry) || now < entry.muleNextVisitAtMs || now < entry.muleVisitCheckAtMs) {
            return;
        }
        entry.muleVisitCheckAtMs = now + VISIT_CHECK_EVERY_MS;
        if (onDuty(entry) || entry.following || entry.shopVisitPending || entry.autopilotErrandMapId != -1
                || !BotShopManager.isCramped(bot, InventoryType.ETC)) {
            return;
        }
        Directory dir = directory(now);
        if (dir == null) {
            entry.muleVisitCheckAtMs = now + 2_000L; // the roster directory is loading
            return;
        }
        Map<Integer, List<Item>> plan = availablePlan(bot, dir);
        if (plan.isEmpty()) {
            return;
        }
        if (bot.getMapId() != BotWorldGraph.FREE_MARKET && BotAutopilotManager.routeForBot(bot, bot.getMapId(),
                BotWorldGraph.FREE_MARKET, BotAutopilotManager.MAX_TRAVEL_HOPS,
                BotAutopilotManager.travelOptions(bot, false)) == null) {
            entry.muleNextVisitAtMs = now + 30 * 60_000L; // no way to the market from here; try again later
            return;
        }
        entry.muleVisitMapId = BotWorldGraph.FREE_MARKET;
        entry.muleVisitBins.clear();
        entry.muleVisitBins.addAll(plan.keySet());
        entry.muleVisitProgress.begin(now);
        entry.muleVisitWaitSinceMs = 0L;
        entry.muleVisitLastDropAtMs = 0L;
        entry.muleVisitNextDropAtMs = 0L;
        entry.muleVisitStand = null;
        for (int mule : plan.keySet()) {
            DUTIES.put(mule, new Duty(bot.getId(), now));
            summoner.summon(mule);
        }
        log.info("storage mules: {} visits the Free Market for bins {}", bot.getName(), plan.keySet());
        BotManager.getInstance().botReply(entry, BotManager.randomReply(START_LINES));
    }

    private static final List<String> START_LINES = List.of(
            "bag's full of mats, meeting my storage guy at the FM",
            "gonna drop my ores off at the FM real quick",
            "etc tab is stuffed, running to the FM to unload");

    /** One tick of the visit; true when it consumed the tick. */
    static boolean tickVisit(BotEntry entry, Character bot, boolean runAiTick) {
        if (!visitActive(entry)) {
            return false;
        }
        long now = System.currentTimeMillis();
        entry.lootInhibitMs = Math.max(entry.lootInhibitMs, 2_000); // never pick its own handoff back up
        if (entry.muleVisitProgress.stalled(now, VISIT_STALL_MS)) {
            finishVisit(entry, bot, "couldn't get to the FM, never mind");
            return false;
        }
        if (bot.getMapId() != BotWorldGraph.FREE_MARKET) {
            boolean moved = BotTravelManager.tickTravel(entry, bot, BotWorldGraph.FREE_MARKET,
                    BotAutopilotManager.MAX_TRAVEL_HOPS, runAiTick, false);
            entry.muleVisitProgress.record(bot, moved, now);
            return moved;
        }
        entry.muleVisitProgress.touch(now);
        if (entry.muleVisitWaitSinceMs == 0L) {
            entry.muleVisitWaitSinceMs = now; // each bin's mule gets its own wait
        }
        entry.muleVisitBins.removeIf(id -> !DUTIES.containsKey(id)); // its login failed or it gave up
        if (entry.muleVisitBins.isEmpty()) {
            finishVisit(entry, bot, "all sorted, back to it");
            return false;
        }
        // Serve whichever mule is here, the one at the head first (mid handoff it stays there); wait for the rest.
        Integer muleId = null;
        BotEntry mule = null;
        for (Integer id : entry.muleVisitBins) {
            BotEntry m = BotManager.getInstance().getEntryByBotCharId(id);
            if (m != null && m.bot != null && !m.loggingOut && m.bot.getMap() == bot.getMap()) {
                muleId = id;
                mule = m;
                break;
            }
        }
        if (mule == null) {
            if (now - entry.muleVisitWaitSinceMs > MULE_WAIT_MS) {
                finishVisit(entry, bot, "my storage guys never showed, oh well"); // their stacks stay on the shelf
                return false;
            }
            return standNear(entry, bot, marketMeetingPoint(bot), runAiTick); // wait by the entry
        }
        if (!muleId.equals(entry.muleVisitBins.peekFirst())) {
            entry.muleVisitBins.remove(muleId);
            entry.muleVisitBins.addFirst(muleId);
        }
        Point mulePos = mule.bot.getPosition();
        if (manhattan(bot.getPosition(), mulePos) > HANDOFF_PX) {
            entry.muleVisitStand = null;
            BotTravelManager.pinMoveTarget(entry, mulePos);
            BotTravelManager.movementStep.step(entry, mulePos, runAiTick);
            return true;
        }
        BotTravelManager.clearMoveTargetPin(entry);
        boolean dropsOut = hasDropsOnFloor(bot);
        if (dropsOut && now - entry.muleVisitLastDropAtMs < PICKUP_WAIT_MS) {
            return true; // the mule is picking the batch up
        }
        if (now < entry.muleVisitNextDropAtMs) {
            return true;
        }
        Directory dir = directory(now);
        List<Item> stacks = planBins(dir, bot.getId(), BotInventoryManager.etcShelf(bot)).getOrDefault(muleId, List.of());
        Inventory muleEtc = mule.bot.getInventory(InventoryType.ETC);
        int room = muleEtc == null ? 0 : muleEtc.getNumFreeSlot();
        if (stacks.isEmpty() || room <= 0 || dropsOut) {
            if (room <= 0) {
                FULL_BINS.put(muleId, now + FULL_BIN_RETRY_MS);
                log.info("storage mules: bin {} is full", mule.bot.getName());
            }
            nextBin(entry, muleId, now); // done, the mule is full, or it stopped picking up
            return true;
        }
        int batch = Math.min(Math.min(BATCH_STACKS, room), stacks.size());
        int dropped = 0;
        for (Item item : stacks) {
            if (dropped >= batch) {
                break;
            }
            if (BotInventoryManager.hasItem(bot, item) && item.getQuantity() > 0) {
                InventoryManipulator.drop(bot.getClient(), InventoryType.ETC, item.getPosition(), item.getQuantity());
                dropped++;
            }
        }
        entry.muleVisitLastDropAtMs = now;
        entry.muleVisitNextDropAtMs = now + BotManager.randMs(1_500, 4_000);
        return true;
    }

    private static boolean standNear(BotEntry entry, Character bot, Point near, boolean runAiTick) {
        if (entry.muleVisitStand == null) {
            entry.muleVisitStand = BotTravelManager.pickReachableApproachPoint(
                    entry, bot, near, BotTravelManager.APPROACH_SPREAD_PX);
        }
        Point stand = entry.muleVisitStand;
        if (stand != null && (entry.inAir || entry.climbing || manhattan(bot.getPosition(), stand) > STAND_PX)) {
            BotTravelManager.pinMoveTarget(entry, stand);
            BotTravelManager.movementStep.step(entry, stand, runAiTick);
        }
        return true;
    }

    private static boolean hasDropsOnFloor(Character bot) {
        for (MapItem drop : bot.getMap().getDroppedItems()) {
            if (drop.isPlayerDrop() && drop.getOwnerId() == bot.getId()
                    && BotLootEligibility.isPresent(bot.getMap(), drop)) {
                return true;
            }
        }
        return false;
    }

    private static void nextBin(BotEntry entry, int muleId, long now) {
        entry.muleVisitBins.remove((Integer) muleId);
        endDuty(muleId);
        entry.muleVisitWaitSinceMs = 0L;
        entry.muleVisitNextDropAtMs = now + BotManager.randMs(1_000, 3_000);
    }

    static void finishVisit(BotEntry entry, Character bot, String say) {
        for (Integer mule : new ArrayList<>(entry.muleVisitBins)) {
            endDuty(mule);
        }
        clearVisit(entry);
        // A visit every hour or two at most: the shelf covers the time between.
        entry.muleNextVisitAtMs = System.currentTimeMillis() + 45 * 60_000L
                + ThreadLocalRandom.current().nextLong(45 * 60_000L);
        if (say != null) {
            BotManager.getInstance().botReply(entry, say);
        }
    }

    static void clearVisit(BotEntry entry) {
        BotTravelManager.clearMoveTargetPin(entry);
        entry.muleVisitMapId = -1;
        entry.muleVisitBins.clear();
        entry.muleVisitProgress.clear();
        entry.muleVisitStand = null;
    }

    private static int manhattan(Point a, Point b) {
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
    }
}
