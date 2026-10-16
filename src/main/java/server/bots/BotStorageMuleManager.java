package server.bots;

import client.Character;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
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
import java.util.function.IntUnaryOperator;

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

    static final String CRAFTING_PARTS_ROLE = "Monster ETC drops";
    // The parts bin keeps enough of each part for this many of the most demanding recipe; past that the part
    // stays on the farmer's shelf and sells when the tab is cramped, so the bin never fills with one kind.
    static final int CRAFTING_PART_BIN_CRAFTS = 3;

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
                    || ItemConstants.isMakerReagent(id) || id == 4011007 || id == 4020009 || id == 4021009),
            // Leather, screws, skins and tails NPC gear recipes need (BotInventoryManager.isCraftingPart).
            new Bin(CRAFTING_PARTS_ROLE, BotInventoryManager::isCraftingPart));


    /** The storage role that takes this item, or null. */
    static String binRole(int itemId) {
        for (Bin bin : BINS) {
            if (bin.takes().test(itemId)) {
                return bin.role();
            }
        }
        return null;
    }

    // ---- Maple items: never sold, each goes to the roster bin for its kind (owner rule, 2026-10-15) --------------

    static final String RARE_EQUIPMENT = "Rare and high-stat equipment";
    private static final String[] CLASSES = {"Warrior", "Mage", "Bowman", "Thief", "Pirate"}; // reqJob bits 1..16
    static final List<String> MAPLE_ROLES = List.of(
            "Warrior weapons", "Mage weapons", "Bowman weapons", "Thief weapons", "Pirate weapons",
            "Warrior armor", "Mage armor", "Bowman armor", "Thief armor", "Pirate armor",
            "Gloves", "Shoes", "Capes", "Accessories", RARE_EQUIPMENT);

    /** Test seam: an equip's reqJob mask from its WZ info (0 = any job). */
    static IntUnaryOperator reqJob = id -> {
        Map<String, Integer> stats = ItemInformationProvider.getInstance().getEquipStats(id);
        return stats == null ? 0 : stats.getOrDefault("reqJob", 0);
    };

    /** The roster bin for a Maple item, by kind: weapons (and Maple Throwing-Stars) and armor by the class that
     *  uses them, then gloves, shoes, capes and accessories; gear any job can wear to the rare equipment bin. */
    static String mapleBinRole(int itemId) {
        if (ItemConstants.isThrowingStar(itemId)) {
            return "Thief weapons";
        }
        if (ItemConstants.isWeapon(itemId)) {
            return byClass(itemId, " weapons");
        }
        return switch (itemId / 10000) {
            case 107 -> "Shoes";
            case 108 -> "Gloves";
            case 110 -> "Capes";
            case 101, 102, 103, 111, 112, 113, 114 -> "Accessories"; // face, eye, earrings, ring, pendant, belt, medal
            default -> byClass(itemId, " armor"); // hats, tops, overalls, pants, shields
        };
    }

    private static String byClass(int itemId, String suffix) {
        int mask = reqJob.applyAsInt(itemId);
        for (int i = 0; i < CLASSES.length; i++) {
            if (mask > 0 && (mask & (1 << i)) != 0) {
                return CLASSES[i] + suffix;
            }
        }
        return RARE_EQUIPMENT;
    }

    /** The bin an item goes to: Maple gear by its kind, a maker material by its family, else null. */
    static String roleFor(int itemId) {
        return BotInventoryManager.isMapleItem(itemId) ? mapleBinRole(itemId) : binRole(itemId);
    }

    /** What a farmer hands over: its ETC shelf, plus the Maple gear it isn't wearing next or holding for a
     *  teammate (equips the sell rules mark BIN), plus Maple Throwing-Stars it doesn't throw itself, all that
     *  survive being dropped. */
    static List<Item> handoffItems(BotEntry entry, Character bot) {
        List<Item> out = new ArrayList<>(BotInventoryManager.etcShelf(bot));
        BotInventoryManager.classifyBagEquips(entry, bot).forEach((item, c) -> {
            if (c.status() == BotInventoryManager.BagEquipStatus.BIN && BotInventoryManager.isSafeToDrop(item)) {
                out.add(item);
            }
        });
        Inventory use = bot.getInventory(InventoryType.USE);
        for (Item item : use == null ? List.<Item>of() : use.list()) {
            int id = item.getItemId();
            if (BotInventoryManager.isMapleItem(id) && !BotInventoryManager.isOwnAmmo(bot, id)
                    && BotInventoryManager.isSafeToDrop(item)) {
                out.add(item);
            }
        }
        return out;
    }

    private static String roleKey(String role) {
        return role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
    }

    // ---- directory: which roster character holds which bin, per registered owner ------------------------------
    // Loaded off the tick thread (a DB scan), refreshed every 30 minutes. Until it is ready, visits don't start.

    /**
     * A fleet member as the roster snapshot saw it: the owner or one of the owner's bots. {@code wornReq} maps a
     * gear slot ({@link #gearSlot}) to the highest required level among what it wears there; {@code bagCopies}
     * counts the equips in its bag by item id (a storage mule's bag is its bin).
     */
    record Member(int charId, int jobId, int level, Map<Integer, Integer> wornReq, Map<Integer, Integer> bagCopies,
                  Map<Integer, Integer> etcHeld, String name, java.util.Set<Integer> wornIds,
                  Map<Integer, Integer> scrollsHeld) {
        Member(int charId, int jobId, int level) {
            this(charId, jobId, level, Map.of(), Map.of(), Map.of());
        }

        Member(int charId, int jobId, int level, Map<Integer, Integer> wornReq, Map<Integer, Integer> bagCopies) {
            this(charId, jobId, level, wornReq, bagCopies, Map.of());
        }

        Member(int charId, int jobId, int level, Map<Integer, Integer> wornReq, Map<Integer, Integer> bagCopies,
               Map<Integer, Integer> etcHeld) {
            this(charId, jobId, level, wornReq, bagCopies, etcHeld, "", java.util.Set.of(), Map.of());
        }
    }

    /** The slot a piece of gear competes for: every weapon is one slot; an overall is worn as top and pants. */
    static int gearSlot(int itemId) {
        return ItemConstants.isWeapon(itemId) ? 130 : itemId / 10000;
    }

    /** Test seam: an equip's required level. */
    static IntUnaryOperator reqLevel = id -> ItemInformationProvider.getInstance().getEquipLevelReq(id);

    record Directory(Map<Integer, Integer> ownerOf, Map<Integer, Map<String, Integer>> mulesByOwner,
                     Map<Integer, String> roleOfMule, Map<Integer, List<Member>> membersByOwner, long loadedAtMs) {
        Directory(Map<Integer, Integer> ownerOf, Map<Integer, Map<String, Integer>> mulesByOwner,
                  Map<Integer, String> roleOfMule, long loadedAtMs) {
            this(ownerOf, mulesByOwner, roleOfMule, Map.of(), loadedAtMs);
        }

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
                    DEMAND.clear();
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
        DEMAND.clear();
    }

    // ---- fleet demand: how many fleet members will wear an equip (the hold value model's copy cap) ----------

    static final int UNKNOWN_DEMAND = Integer.MAX_VALUE;
    static final int DEMAND_LEAD_LEVELS = 10;     // a member this many levels short will wear it soon
    static final int DEMAND_OUTGROWN_LEVELS = 30; // a member this far past its level has moved on
    private static final int ANY_STAT = 30_000;   // stat requirements grow with levels; only job and level gate
    private static final Map<Long, Integer> DEMAND = new ConcurrentHashMap<>(); // (holder, item) for this snapshot

    /** Test seam: whether a member wears this equip now or within the lead, by the player requirement check. */
    interface WearCheck {
        boolean wears(Member member, Equip equip);
    }

    static WearCheck wearCheck = (member, equip) -> {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        if (ii.getEquipLevelReq(equip.getItemId()) < member.level() - DEMAND_OUTGROWN_LEVELS) {
            return false;
        }
        client.Job job = client.Job.getById(member.jobId());
        return job != null && ii.meetsEquipRequirements(equip, job, member.level() + DEMAND_LEAD_LEVELS,
                ANY_STAT, ANY_STAT, ANY_STAT, ANY_STAT, Short.MAX_VALUE);
    };

    /**
     * How many more copies of this equip the holder's fleet (its owner and the owner's other bots) will wear: the
     * members who will wear it now or within {@link #DEMAND_LEAD_LEVELS} levels, without having outgrown it,
     * and who don't already wear gear of at least its level in that slot; less the copies already in fleet
     * bags and bins. Beginners don't count as wearers: every storage mule is one, and a beginner wears almost
     * nothing. {@link #UNKNOWN_DEMAND} until the roster snapshot loads or when the holder has no registered
     * owner, so nothing sells on a guess.
     */
    static int fleetDemand(Character holder, Equip equip) {
        Directory d = directory(System.currentTimeMillis());
        Integer owner = d == null || holder == null ? null : d.ownerOf().get(holder.getId());
        if (owner == null) {
            return UNKNOWN_DEMAND;
        }
        long key = ((long) holder.getId() << 32) | (equip.getItemId() & 0xffffffffL);
        return DEMAND.computeIfAbsent(key, k ->
                countDemand(d.membersByOwner().getOrDefault(owner, List.of()), holder.getId(), equip));
    }

    static int countDemand(List<Member> members, int holderId, Equip equip) {
        int id = equip.getItemId();
        int slot = gearSlot(id);
        int req = reqLevel.applyAsInt(id);
        int wanting = 0;
        int held = 0;
        for (Member m : members) {
            if (m.charId() == holderId) {
                continue; // what the holder itself wears next is its own upgrade, not fleet stock
            }
            held += m.bagCopies().getOrDefault(id, 0);
            boolean covered = m.wornReq().getOrDefault(slot, -1) >= req;
            if (m.jobId() != 0 && !covered && wearCheck.wears(m, equip)) {
                wanting++;
            }
        }
        return Math.max(0, wanting - held);
    }

    private static Directory loadDirectory(long nowMs) throws Exception {
        java.util.Set<String> binRoles = new java.util.HashSet<>();
        for (Bin bin : BINS) {
            binRoles.add(roleKey(bin.role()));
        }
        for (String role : MAPLE_ROLES) {
            binRoles.add(roleKey(role));
        }
        Map<Integer, Integer> ownerOf = new HashMap<>();
        Map<Integer, Map<String, Integer>> mulesByOwner = new HashMap<>();
        Map<Integer, String> roleOfMule = new HashMap<>();
        Map<Integer, List<Member>> membersByOwner = new HashMap<>();
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT o.bot_char_id, o.owner_char_id, b.config, c.job, c.level, c.name FROM bot_owners o "
                            + "LEFT JOIN bot_config b ON b.bot_char_id=o.bot_char_id "
                            + "LEFT JOIN characters c ON c.id=o.bot_char_id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int bot = rs.getInt("bot_char_id");
                    int owner = rs.getInt("owner_char_id");
                    ownerOf.put(bot, owner);
                    membersByOwner.computeIfAbsent(owner, k -> new ArrayList<>())
                            .add(named(new Member(bot, rs.getInt("job"), rs.getInt("level")), rs.getString("name")));
                    String role = roleKey(BotPersonality.parse(rs.getString("config")).rosterRole());
                    if (binRoles.contains(role)) {
                        mulesByOwner.computeIfAbsent(owner, k -> new HashMap<>()).put(role, bot);
                        roleOfMule.put(bot, role);
                    }
                }
            }
            // The owners themselves play too.
            try (PreparedStatement ps = con.prepareStatement("SELECT job, level, name FROM characters WHERE id=?")) {
                for (Map.Entry<Integer, List<Member>> e : membersByOwner.entrySet()) {
                    ps.setInt(1, e.getKey());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            e.getValue().add(named(new Member(e.getKey(), rs.getInt("job"), rs.getInt("level")),
                                    rs.getString("name")));
                        }
                    }
                }
            }
            loadFleetGear(con, membersByOwner);
        }
        log.info("storage mules: {} bins across {} owners, {} fleet members", roleOfMule.size(),
                mulesByOwner.size(), membersByOwner.values().stream().mapToInt(List::size).sum());
        return new Directory(ownerOf, mulesByOwner, roleOfMule, membersByOwner, nowMs);
    }

    private static Member named(Member m, String name) {
        return new Member(m.charId(), m.jobId(), m.level(), m.wornReq(), m.bagCopies(), m.etcHeld(),
                name == null ? "" : name, m.wornIds(), m.scrollsHeld());
    }

    /** Fills in each member's worn gear (levels by slot, and the item ids), bag equips, equip scrolls and ETC
     *  holdings from the saved inventories (type 1 = a character's own items; inventorytype -1 worn, 1 the equip
     *  bag, 2 the use bag, 4 the ETC bag). */
    private static void loadFleetGear(Connection con, Map<Integer, List<Member>> membersByOwner) throws Exception {
        Map<Integer, Map<Integer, Integer>> worn = new HashMap<>();
        Map<Integer, java.util.Set<Integer>> wornIds = new HashMap<>();
        Map<Integer, Map<Integer, Integer>> bags = new HashMap<>();
        Map<Integer, Map<Integer, Integer>> scrolls = new HashMap<>();
        Map<Integer, Map<Integer, Integer>> etc = new HashMap<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT characterid, itemid, inventorytype, quantity FROM inventoryitems WHERE type=1 AND inventorytype IN (-1,1,2,4) "
                        + "AND characterid IN (SELECT bot_char_id FROM bot_owners UNION SELECT owner_char_id FROM bot_owners)");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int charId = rs.getInt("characterid");
                int itemId = rs.getInt("itemid");
                if (rs.getInt("inventorytype") == 4) {
                    etc.computeIfAbsent(charId, k -> new HashMap<>()).merge(itemId, rs.getInt("quantity"), Integer::sum);
                    continue;
                }
                if (rs.getInt("inventorytype") == 2) {
                    if (ItemConstants.isEquipScroll(itemId)) {
                        scrolls.computeIfAbsent(charId, k -> new HashMap<>()).merge(itemId, rs.getInt("quantity"), Integer::sum);
                    }
                    continue;
                }
                if (rs.getInt("inventorytype") == 1) {
                    bags.computeIfAbsent(charId, k -> new HashMap<>()).merge(itemId, 1, Integer::sum);
                    continue;
                }
                wornIds.computeIfAbsent(charId, k -> new java.util.HashSet<>()).add(itemId);
                Map<Integer, Integer> slots = worn.computeIfAbsent(charId, k -> new HashMap<>());
                int req = reqLevel.applyAsInt(itemId);
                int slot = gearSlot(itemId);
                List<Integer> covers = slot == 105 ? List.of(104, 105, 106) : List.of(slot); // an overall is top and pants
                for (int s : covers) {
                    slots.merge(s, req, Math::max);
                }
            }
        }
        for (List<Member> members : membersByOwner.values()) {
            members.replaceAll(m -> new Member(m.charId(), m.jobId(), m.level(), worn.getOrDefault(m.charId(), Map.of()),
                    bags.getOrDefault(m.charId(), Map.of()), etc.getOrDefault(m.charId(), Map.of()), m.name(),
                    wornIds.getOrDefault(m.charId(), java.util.Set.of()), scrolls.getOrDefault(m.charId(), Map.of())));
        }
    }

    // ---- mule duty (mule side) -------------------------------------------------------------------------------

    /** A mule's duty: who it serves, since when, and on a pickup what it still hands that partner ({@code give},
     *  item id to quantity; null on a visit, where it takes the farmer's drops). Only the mule's tick changes it. */
    record Duty(int partnerId, long sinceMs, Map<Integer, Integer> give) {
        Duty(int partnerId, long sinceMs) {
            this(partnerId, sinceMs, null);
        }

        boolean pickup() {
            return give != null;
        }
    }

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

    /** A handoff drop its receiver may take at once (no 15 s wait for bot drops): the farmer's drop for the mule
     *  on duty for it, or a mule's drop for the partner it gives to. */
    static boolean isPartnerDrop(Character looter, MapItem drop) {
        if (looter == null || drop == null || !drop.isPlayerDrop()) {
            return false;
        }
        Duty duty = DUTIES.get(looter.getId());
        if (duty != null && !duty.pickup() && duty.partnerId() == drop.getOwnerId()) {
            return true;
        }
        Duty giver = DUTIES.get(drop.getOwnerId());
        return giver != null && giver.pickup() && giver.partnerId() == looter.getId();
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
        boolean wanted = partner != null && partner.bot != null && (duty.pickup()
                ? partner.craftPickups.containsKey(bot.getId())
                : partner.muleVisitMapId != -1 && partner.muleVisitBins.contains(bot.getId()));
        if (now - duty.sinceMs() > DUTY_MAX_MS || !wanted) {
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
        if (duty.pickup()) {
            return give(entry, bot, partner.bot, duty, now, runAiTick);
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
            return waitAtMeetingPoint(entry, bot, runAiTick);
        }
        if (entry.inAir || entry.climbing || manhattan(bot.getPosition(), target) > STAND_PX) {
            BotTravelManager.pinMoveTarget(entry, target);
            BotTravelManager.movementStep.step(entry, target, runAiTick);
        }
        return true;
    }

    private static boolean waitAtMeetingPoint(BotEntry entry, Character bot, boolean runAiTick) {
        if (entry.muleStandSpot == null) {
            entry.muleStandSpot = BotTravelManager.pickReachableApproachPoint(
                    entry, bot, marketMeetingPoint(bot), BotTravelManager.APPROACH_SPREAD_PX);
        }
        Point target = entry.muleStandSpot;
        if (target != null && (entry.inAir || entry.climbing || manhattan(bot.getPosition(), target) > STAND_PX)) {
            BotTravelManager.pinMoveTarget(entry, target);
            BotTravelManager.movementStep.step(entry, target, runAiTick);
        }
        return true;
    }

    /**
     * A pickup: the mule walks up to its partner and drops what it was asked for from its ETC tab, a stack at a
     * time, the way the farmer hands a batch over; the partner's passive loot takes it. The partner ends the
     * duty once it holds what it needs.
     */
    private static boolean give(BotEntry entry, Character bot, Character partner, Duty duty, long now,
                                boolean runAiTick) {
        entry.lootInhibitMs = Math.max(entry.lootInhibitMs, 2_000); // never pick its own handoff back up
        if (partner.getMap() != bot.getMap()) {
            return waitAtMeetingPoint(entry, bot, runAiTick);
        }
        Point at = partner.getPosition();
        if (entry.inAir || entry.climbing || manhattan(bot.getPosition(), at) > HANDOFF_PX) {
            BotTravelManager.pinMoveTarget(entry, at);
            BotTravelManager.movementStep.step(entry, at, runAiTick);
            return true;
        }
        BotTravelManager.clearMoveTargetPin(entry);
        if (now < entry.muleNextGiveAtMs) {
            return true;
        }
        Inventory etc = bot.getInventory(InventoryType.ETC);
        for (Map.Entry<Integer, Integer> want : duty.give().entrySet()) {
            Item stack = null;
            for (Item it : etc == null ? List.<Item>of() : etc.list()) {
                if (it.getItemId() == want.getKey() && it.getQuantity() > 0 && BotInventoryManager.isSafeToDrop(it)) {
                    stack = it;
                    break;
                }
            }
            if (stack == null) {
                duty.give().remove(want.getKey()); // the bin ran out since the snapshot
                return true;
            }
            int n = Math.min(want.getValue(), stack.getQuantity());
            InventoryManipulator.drop(bot.getClient(), InventoryType.ETC, stack.getPosition(), (short) n);
            if (n >= want.getValue()) {
                duty.give().remove(want.getKey());
            } else {
                duty.give().put(want.getKey(), want.getValue() - n);
            }
            entry.muleNextGiveAtMs = now + BotManager.randMs(1_500, 4_000);
            return true;
        }
        return true; // all handed over; the partner ends the duty once it has picked it up
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
     * The farmer's handoff grouped by the mule that takes it, in order: mule char id to the stacks it gets.
     * Items no bin takes, or whose bin has no mule for this owner, stay out (the shelf still sells materials;
     * a Maple item waits in the bag).
     */
    static Map<Integer, List<Item>> planBins(Directory dir, int farmerId, List<Item> shelf) {
        Map<Integer, List<Item>> plan = new LinkedHashMap<>();
        if (dir == null) {
            return plan;
        }
        Map<Integer, Integer> partsPlanned = new HashMap<>();
        for (Item item : shelf) {
            String role = roleFor(item.getItemId());
            Integer mule = role == null ? null : dir.muleFor(farmerId, role);
            if (mule == null || mule == farmerId) {
                continue;
            }
            if (CRAFTING_PARTS_ROLE.equals(role)) {
                int id = item.getItemId();
                int held = binHolds(dir, farmerId, mule, id) + partsPlanned.getOrDefault(id, 0);
                if (held >= CRAFTING_PART_BIN_CRAFTS * BotInventoryManager.craftingPartMaxPerRecipe(id)) {
                    continue; // the bin has enough of this part
                }
                partsPlanned.merge(id, (int) item.getQuantity(), Integer::sum);
            }
            plan.computeIfAbsent(mule, k -> new ArrayList<>()).add(item);
        }
        return plan;
    }

    /** How much of an ETC item the mule held at the last roster snapshot. */
    private static int binHolds(Directory dir, int farmerId, int muleId, int itemId) {
        Integer owner = dir.ownerOf().get(farmerId);
        for (Member m : owner == null ? List.<Member>of() : dir.membersByOwner().getOrDefault(owner, List.of())) {
            if (m.charId() == muleId) {
                return m.etcHeld().getOrDefault(itemId, 0);
            }
        }
        return 0;
    }

    /** The bins this farmer can visit now: {@link #planBins} minus mules someone else has (a player logged it
     *  in, or another farmer's visit holds it). */
    private static Map<Integer, List<Item>> availablePlan(Character bot, Directory dir, List<Item> items) {
        Map<Integer, List<Item>> plan = planBins(dir, bot.getId(), items);
        long now = System.currentTimeMillis();
        plan.keySet().removeIf(mule -> !muleFree(mule) || now < FULL_BINS.getOrDefault(mule, 0L));
        return plan;
    }

    /** No visit or pickup holds this mule, and no player has it logged in. */
    private static boolean muleFree(int mule) {
        if (DUTIES.containsKey(mule)) {
            return false;
        }
        BotEntry m = BotManager.getInstance().getEntryByBotCharId(mule);
        return m == null || m.bot == null || m.owner == m.bot;
    }

    // ---- pickups: a farmer takes materials back out of its bins (crafting on demand, BotCraftManager) ----------

    /** What this farmer's free bins held at the last roster snapshot: mule char id to ETC item to quantity.
     *  Empty while the snapshot loads or when the farmer has no registered owner. */
    static Map<Integer, Map<Integer, Integer>> binStock(Character farmer) {
        Directory dir = directory(System.currentTimeMillis());
        return dir == null || farmer == null ? Map.of() : binStock(dir, farmer.getId(), BotStorageMuleManager::muleFree);
    }

    static Map<Integer, Map<Integer, Integer>> binStock(Directory dir, int farmerId, IntPredicate free) {
        Integer owner = dir.ownerOf().get(farmerId);
        Map<Integer, String> mules = new HashMap<>();
        Map<String, Integer> byRole = owner == null ? null : dir.mulesByOwner().get(owner);
        Map<Integer, Map<Integer, Integer>> stock = new LinkedHashMap<>();
        if (byRole == null) {
            return stock;
        }
        byRole.forEach((role, mule) -> mules.put(mule, role));
        for (Member m : dir.membersByOwner().getOrDefault(owner, List.of())) {
            if (mules.containsKey(m.charId()) && m.charId() != farmerId && !m.etcHeld().isEmpty() && free.test(m.charId())) {
                stock.put(m.charId(), m.etcHeld());
            }
        }
        return stock;
    }

    /** Who gives what: each wanted item taken from the bins that hold it, lowest mule id first, until covered. */
    static Map<Integer, Map<Integer, Integer>> assignPickups(Map<Integer, Map<Integer, Integer>> stock,
                                                             Map<Integer, Integer> want) {
        Map<Integer, Map<Integer, Integer>> out = new LinkedHashMap<>();
        List<Integer> mules = new ArrayList<>(stock.keySet());
        mules.sort(null);
        want.forEach((item, qty) -> {
            int need = qty;
            for (int mule : mules) {
                if (need <= 0) {
                    break;
                }
                int take = Math.min(need, stock.get(mule).getOrDefault(item, 0));
                if (take > 0) {
                    out.computeIfAbsent(mule, k -> new LinkedHashMap<>()).put(item, take);
                    need -= take;
                }
            }
        });
        return out;
    }

    /** Puts each mule on duty to give its share to the farmer and wakes it; false (nothing claimed) when one
     *  is no longer free. The farmer records the pickups before calling, so a mule's first tick sees them. */
    static boolean claimPickups(int farmerId, Map<Integer, Map<Integer, Integer>> pickups) {
        long now = System.currentTimeMillis();
        List<Integer> claimed = new ArrayList<>();
        for (Map.Entry<Integer, Map<Integer, Integer>> e : pickups.entrySet()) {
            if (!muleFree(e.getKey())
                    || DUTIES.putIfAbsent(e.getKey(), new Duty(farmerId, now, new ConcurrentHashMap<>(e.getValue()))) != null) {
                claimed.forEach(DUTIES::remove);
                return false;
            }
            claimed.add(e.getKey());
        }
        claimed.forEach(summoner::summon);
        return true;
    }

    /** True while this mule is on a pickup duty for the farmer (its login hasn't failed, it hasn't given up). */
    static boolean givingTo(int muleId, int farmerId) {
        Duty d = DUTIES.get(muleId);
        return d != null && d.partnerId() == farmerId && d.pickup();
    }

    /** Ends a pickup duty this farmer holds; the mule logs out. */
    static void releasePickup(int muleId, int farmerId) {
        Duty d = DUTIES.get(muleId);
        if (d != null && d.partnerId() == farmerId) {
            endDuty(muleId);
        }
    }

    /** The farmer at the market during a pickup: walk onto the newest drop one of its mules left, else wait by
     *  the entry where the mules arrive. */
    static boolean collectPickup(BotEntry entry, Character bot, java.util.Set<Integer> mules, boolean runAiTick) {
        for (MapItem drop : bot.getMap().getDroppedItems()) {
            if (drop.isPlayerDrop() && mules.contains(drop.getOwnerId()) && BotLootEligibility.isPresent(bot.getMap(), drop)) {
                Point at = drop.getPosition();
                if (entry.inAir || entry.climbing || manhattan(bot.getPosition(), at) > STAND_PX) {
                    entry.muleVisitStand = null;
                    BotTravelManager.pinMoveTarget(entry, at);
                    BotTravelManager.movementStep.step(entry, at, runAiTick);
                }
                return true;
            }
        }
        return standNear(entry, bot, marketMeetingPoint(bot), runAiTick);
    }

    /**
     * True while the shelf's materials are spoken for by a mule: a visit is under way, or one is due (an
     * autopilot farmer off cooldown with stacks a free mule takes). The cramped shop trip then leaves the
     * shelf alone and sells only true trash. A visit that ends, well or not, starts the cooldown, so
     * whatever stayed behind sells on the next trip.
     */
    static boolean holdsShelf(BotEntry entry, Character bot) {
        if (visitActive(entry) || BotCraftManager.active(entry)) {
            return true; // a craft errand is about to use the materials
        }
        if (entry == null || bot == null || entry.following || !BotAutopilotManager.isActive(entry)
                || System.currentTimeMillis() < entry.muleNextVisitAtMs) {
            return false;
        }
        Directory dir = directory(System.currentTimeMillis());
        return dir != null && !availablePlan(bot, dir, BotInventoryManager.etcShelf(bot)).isEmpty();
    }

    /** Start a visit when the ETC tab is cramped, or the bag holds Maple gear, and a free mule takes some of it. */
    static void maybeStartVisit(BotEntry entry, Character bot) {
        long now = System.currentTimeMillis();
        if (visitActive(entry) || now < entry.muleNextVisitAtMs || now < entry.muleVisitCheckAtMs) {
            return;
        }
        entry.muleVisitCheckAtMs = now + VISIT_CHECK_EVERY_MS;
        if (onDuty(entry) || entry.following || entry.shopVisitPending || entry.autopilotErrandMapId != -1
                || BotCraftManager.active(entry)) {
            return;
        }
        Directory dir = directory(now);
        if (dir == null) {
            entry.muleVisitCheckAtMs = now + 2_000L; // the roster directory is loading
            return;
        }
        Map<Integer, List<Item>> plan = availablePlan(bot, dir, handoffItems(entry, bot));
        boolean maple = plan.values().stream().flatMap(List::stream)
                .anyMatch(item -> BotInventoryManager.isMapleItem(item.getItemId()));
        if (plan.isEmpty() || !(maple || BotShopManager.isCramped(bot, InventoryType.ETC))) {
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
        entry.muleVisitServed.clear();
        entry.muleVisitRefillAtMs = 0L;
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
            "got some maple gear, dropping it with my storage guys at the FM",
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
        entry.muleVisitBins.removeIf(id -> { // its login failed or it gave up
            boolean gone = !DUTIES.containsKey(id);
            if (gone) {
                entry.muleVisitServed.add(id);
            }
            return gone;
        });
        if (entry.muleVisitBins.isEmpty()) {
            // Before leaving, call in any mule it still needs that has come free (another farmer had it).
            if (now - entry.muleVisitWaitSinceMs > MULE_WAIT_MS || !refillBins(entry, bot, now)) {
                finishVisit(entry, bot, "all sorted, back to it");
                return false;
            }
            if (entry.muleVisitBins.isEmpty()) {
                return standNear(entry, bot, marketMeetingPoint(bot), runAiTick); // its mule is still busy
            }
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
        List<Item> stacks = planBins(dir, bot.getId(), handoffItems(entry, bot)).getOrDefault(muleId, List.of());
        if (stacks.isEmpty() || dropsOut) {
            nextBin(entry, muleId, now); // done, or it stopped picking up
            return true;
        }
        // A batch never holds more than the mule has room for, per tab (a Maple weapon needs an equip slot).
        Map<InventoryType, Integer> room = new java.util.EnumMap<>(InventoryType.class);
        Character muleChar = mule.bot;
        int dropped = 0;
        for (Item item : stacks) {
            if (dropped >= BATCH_STACKS) {
                break;
            }
            InventoryType type = item.getInventoryType();
            int free = room.computeIfAbsent(type, t -> {
                Inventory inv = muleChar.getInventory(t);
                return inv == null ? 0 : (int) inv.getNumFreeSlot();
            });
            if (free > 0 && BotInventoryManager.hasItem(bot, item) && item.getQuantity() > 0) {
                InventoryManipulator.drop(bot.getClient(), type, item.getPosition(), item.getQuantity());
                room.put(type, free - 1);
                dropped++;
            }
        }
        if (dropped == 0) {
            FULL_BINS.put(muleId, now + FULL_BIN_RETRY_MS);
            log.info("storage mules: bin {} is full", mule.bot.getName());
            nextBin(entry, muleId, now);
            return true;
        }
        entry.muleVisitLastDropAtMs = now;
        entry.muleVisitNextDropAtMs = now + BotManager.randMs(1_500, 4_000);
        return true;
    }

    private static final long REFILL_CHECK_EVERY_MS = 5_000L;

    /**
     * Claims the mules this visit still needs that weren't free when it started, once they are. True while
     * there is something to wait for: a mule it just claimed, or one still busy with another farmer. False
     * when nothing it holds has a mule left to go to (the visit can end).
     */
    private static boolean refillBins(BotEntry entry, Character bot, long now) {
        if (now < entry.muleVisitRefillAtMs) {
            return entry.muleVisitRefillWaiting;
        }
        entry.muleVisitRefillAtMs = now + REFILL_CHECK_EVERY_MS;
        Directory dir = directory(now);
        Map<Integer, List<Item>> wanted = planBins(dir, bot.getId(), handoffItems(entry, bot));
        wanted.keySet().removeAll(entry.muleVisitServed);
        boolean busy = false;
        List<Integer> claimed = new ArrayList<>();
        for (int mule : wanted.keySet()) {
            if (now < FULL_BINS.getOrDefault(mule, 0L)) {
                continue;
            }
            BotEntry m = BotManager.getInstance().getEntryByBotCharId(mule);
            if (m != null && m.bot != null && m.owner != m.bot && !DUTIES.containsKey(mule)) {
                continue; // a player has it
            }
            if (DUTIES.putIfAbsent(mule, new Duty(bot.getId(), now)) != null) {
                busy = true; // another farmer's visit still holds it
                continue;
            }
            entry.muleVisitBins.add(mule); // before the login, so the mule's first tick sees it is wanted
            summoner.summon(mule);
            claimed.add(mule);
        }
        if (!claimed.isEmpty()) {
            entry.muleVisitWaitSinceMs = now;
            log.info("storage mules: {} calls in freed bins {}", bot.getName(), claimed);
        }
        entry.muleVisitRefillWaiting = busy || !claimed.isEmpty();
        return entry.muleVisitRefillWaiting;
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
        entry.muleVisitServed.add(muleId);
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
        entry.muleVisitServed.clear();
        entry.muleVisitRefillAtMs = 0L;
        entry.muleVisitRefillWaiting = false;
        entry.muleVisitProgress.clear();
        entry.muleVisitStand = null;
    }

    private static int manhattan(Point a, Point b) {
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
    }
}
