package server.bots;

import client.Character;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Scrolls move through the storage bins to the fleet member who can use them, the way a player parks a stack on
 * a storage character for a friend. The holder drops a scroll it can't use but a member of its fleet can
 * ({@link BotOfferManager#scrollUsefulTo}, {@link BotOfferManager#scrollTakers}) with its other materials on its
 * next Free Market visit, into the bin for the gear kind the scroll applies to. Later a member that scrolls its
 * own gear sees in the roster snapshot that a bin holds scrolls for what it wears and fetches as many as it has
 * open slots for, through the same pickup the craft errand uses; its scroll pass spends them on the next town
 * break. A scroll no member can use stays with its holder. Design: docs/bot/etc-storage-mules.md.
 */
final class BotScrollRouting {
    private static final Logger log = LoggerFactory.getLogger(BotScrollRouting.class);

    private BotScrollRouting() {}

    // ---- holder side ------------------------------------------------------------------------------------------

    /** The scrolls this bot carries that it can't use but a fleet member can, for its next Free Market visit. */
    static List<Item> handoffScrolls(Character bot, BotStorageMuleManager.Directory dir) {
        Inventory use = bot.getInventory(InventoryType.USE);
        if (dir == null || use == null) {
            return List.of();
        }
        BotOfferManager.ScrollRules rules = BotOfferManager.rules();
        List<Item> out = new ArrayList<>();
        for (Item item : use.list()) {
            int sid = item.getItemId();
            if (ItemConstants.isEquipScroll(sid) && rules.offerable(sid) && BotInventoryManager.isSafeToDrop(item)
                    && !BotOfferManager.scrollUsefulTo(bot, sid)
                    && !BotOfferManager.scrollTakers(dir, bot.getId(), sid).isEmpty()) {
                out.add(item);
            }
        }
        return out;
    }

    /** The bin a routed scroll goes to: the bin of the gear kind it applies to, by the class most of its takers
     *  play; null when no member of the holder's fleet can use it. */
    static String binRole(BotStorageMuleManager.Directory dir, int holderId, int scrollId) {
        List<BotStorageMuleManager.Member> takers = BotOfferManager.scrollTakers(dir, holderId, scrollId);
        if (takers.isEmpty()) {
            return null;
        }
        return BotStorageMuleManager.kindRole(kindOf(scrollId), () -> mostCommonClass(takers));
    }

    /** The equip kind ({@code itemId / 10000}) a scroll applies to: scroll 204xx yy is for kind 1xx. */
    static int kindOf(int scrollId) {
        return 100 + (scrollId / 100) % 100;
    }

    private static String mostCommonClass(List<BotStorageMuleManager.Member> takers) {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (BotStorageMuleManager.Member m : takers) {
            String cls = BotStorageMuleManager.classOfJob(m.jobId());
            if (cls != null) {
                count.merge(cls, 1, Integer::sum);
            }
        }
        return count.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    // ---- fleet supply: the scrolls the fleet can hand a member (the gear scorer counts them) --------------------

    /** Per snapshot: each owner's spare scrolls (every bin's, and those stranded with members who can't use them)
     *  and each member's own useful scrolls, by gear kind then scroll id. */
    record Supply(BotStorageMuleManager.Directory dir,
                          Map<Integer, Map<Integer, Map<Integer, Integer>>> spareByOwner,
                          Map<Integer, Map<Integer, Map<Integer, Integer>>> ownByMember) {}

    private static volatile Supply supply;

    /** The scrolls of one gear kind the bot's fleet can hand it, by scroll id; empty without a snapshot or owner.
     *  Its own useful ones and the fleet's spare ones: what {@link #handoffScrolls} and the pickup would bring. */
    static Map<Integer, Integer> supply(Character bot, int kind) {
        BotStorageMuleManager.Directory dir = BotStorageMuleManager.directory(System.currentTimeMillis());
        Integer owner = dir == null || bot == null ? null : dir.ownerOf().get(bot.getId());
        if (owner == null) {
            return Map.of();
        }
        Supply s = supplyFor(dir);
        Map<Integer, Integer> spare = s.spareByOwner().getOrDefault(owner, Map.of()).getOrDefault(kind, Map.of());
        Map<Integer, Integer> own = s.ownByMember().getOrDefault(bot.getId(), Map.of()).getOrDefault(kind, Map.of());
        if (own.isEmpty()) {
            return spare;
        }
        Map<Integer, Integer> all = new HashMap<>(spare);
        own.forEach((sid, q) -> all.merge(sid, q, Integer::sum));
        return all;
    }

    private static Supply supplyFor(BotStorageMuleManager.Directory dir) {
        Supply s = supply;
        if (s != null && s.dir() == dir) {
            return s;
        }
        synchronized (BotScrollRouting.class) {
            if (supply == null || supply.dir() != dir) {
                supply = buildSupply(dir, BotOfferManager.rules());
            }
            return supply;
        }
    }

    static Supply buildSupply(BotStorageMuleManager.Directory dir, BotOfferManager.ScrollRules rules) {
        Map<Integer, Map<Integer, Map<Integer, Integer>>> spare = new HashMap<>();
        Map<Integer, Map<Integer, Map<Integer, Integer>>> own = new HashMap<>();
        dir.membersByOwner().forEach((owner, fleet) -> {
            for (BotStorageMuleManager.Member m : fleet) {
                boolean bin = dir.roleOfMule().containsKey(m.charId());
                m.scrollsHeld().forEach((sid, q) -> {
                    if (!rules.offerable(sid)) {
                        return;
                    }
                    boolean useful = !bin && BotOfferManager.scrollUsefulTo(rules, m, sid);
                    Map<Integer, Map<Integer, Map<Integer, Integer>>> into = useful ? own : spare;
                    into.computeIfAbsent(useful ? m.charId() : owner, k -> new HashMap<>())
                            .computeIfAbsent(kindOf(sid), k -> new HashMap<>()).merge(sid, q, Integer::sum);
                });
            }
        });
        return new Supply(dir, spare, own);
    }

    /** Test hook: the supply for this snapshot, built with these item rules. */
    static void setSupplyForTest(BotStorageMuleManager.Directory dir, BotOfferManager.ScrollRules rules) {
        supply = dir == null ? null : buildSupply(dir, rules);
    }

    // ---- taker side: the pickup errand ------------------------------------------------------------------------

    /** A scroll the bins hold that is useful to this bot: its gear kind, how many, and its expected offense gain
     *  per use (success times the stat gain, the scroll planner's per slot worth). */
    record Stock(int scrollId, int kind, int qty, double ev) {}

    /** How many of each to fetch: per gear kind, up to its room, best expected gain first. */
    static Map<Integer, Integer> wanted(List<Stock> stock, Map<Integer, Integer> roomByKind) {
        Map<Integer, Integer> room = new HashMap<>(roomByKind);
        Map<Integer, Integer> out = new TreeMap<>();
        List<Stock> best = new ArrayList<>(stock);
        best.sort(Comparator.comparingDouble(Stock::ev).reversed().thenComparingInt(Stock::scrollId));
        for (Stock s : best) {
            int r = room.getOrDefault(s.kind(), 0);
            int take = Math.min(r, s.qty());
            if (take > 0) {
                out.merge(s.scrollId(), take, Integer::sum);
                room.put(s.kind(), r - take);
            }
        }
        return out;
    }

    /** Open upgrade slots, by kind, on the gear it wears and on bag pieces that already beat what it wears in their
     *  slot by the gear score (a base it means to scroll up), less the useful scrolls already in its bag. */
    static Map<Integer, Integer> roomByKind(Character bot) {
        BotOfferManager.ScrollRules rules = BotOfferManager.rules();
        Map<Integer, Integer> room = new HashMap<>();
        for (Item it : bot.getInventory(InventoryType.EQUIPPED).list()) {
            if (it instanceof Equip e && !rules.isCash(e.getItemId()) && e.getUpgradeSlots() > 0) {
                room.merge(e.getItemId() / 10000, (int) e.getUpgradeSlots(), Integer::sum);
            }
        }
        server.ItemInformationProvider ii = server.ItemInformationProvider.getInstance();
        Inventory bag = bot.getInventory(InventoryType.EQUIP);
        for (Item it : bag == null ? List.<Item>of() : bag.list()) {
            if (it instanceof Equip e && !rules.isCash(e.getItemId()) && e.getUpgradeSlots() > 0
                    && BotScrollManager.wearable(bot, ii, e) && beatsWorn(bot, ii, e)) {
                room.merge(e.getItemId() / 10000, (int) e.getUpgradeSlots(), Integer::sum);
            }
        }
        Inventory use = bot.getInventory(InventoryType.USE);
        for (Item it : use == null ? List.<Item>of() : use.list()) {
            int sid = it.getItemId();
            if (ItemConstants.isEquipScroll(sid) && rules.offerable(sid) && BotOfferManager.scrollUsefulTo(bot, sid)) {
                room.merge(kindOf(sid), -it.getQuantity(), Integer::sum);
            }
        }
        return room;
    }

    private static boolean beatsWorn(Character bot, server.ItemInformationProvider ii, Equip e) {
        Short slot = BotScrollManager.primarySlot(ii, e.getItemId());
        Equip worn = slot == null ? null : BotScrollManager.wornInSlot(bot, ii, slot);
        return slot != null && (worn == null || BotScrollManager.potentialValue(bot, ii, e)
                > BotScrollManager.potentialValue(bot, ii, worn));
    }

    private static final long CHECK_EVERY_MS = 20 * 60_000L;
    private static final long STALL_MS = 6 * 60_000L;
    // After a pickup the snapshot still shows what was taken until it reloads (30 minutes): look again later.
    private static final long AFTER_PICKUP_MS = 45 * 60_000L;

    static boolean active(BotEntry entry) {
        return entry != null && entry.scrollPickupActive;
    }

    private static boolean busy(BotEntry entry) {
        return entry.following || entry.shopVisitPending || entry.autopilotErrandMapId != -1
                || entry.jobErrandMapId != -1 || entry.questErrandMapId != -1 || entry.gachaErrandMapId != -1
                || BotStorageMuleManager.visitActive(entry) || BotStorageMuleManager.onDuty(entry)
                || BotCraftManager.active(entry);
    }

    /** Now and then, when the bot scrolls its own gear and a bin holds scrolls for it, start a pickup. */
    static void maybeStart(BotEntry entry, Character bot) {
        long now = System.currentTimeMillis();
        if (active(entry) || now < entry.scrollPickupCheckAtMs) {
            return;
        }
        entry.scrollPickupCheckAtMs = now + CHECK_EVERY_MS + ThreadLocalRandom.current().nextLong(CHECK_EVERY_MS);
        if (!entry.selfScrollEnabled || busy(entry) || bot.getMap() == null) {
            return;
        }
        BotStorageMuleManager.Directory dir = BotStorageMuleManager.directory(now);
        if (dir == null) {
            entry.scrollPickupCheckAtMs = now + 5_000L; // the roster snapshot is loading
            return;
        }
        Map<Integer, Map<Integer, Integer>> bins = BotStorageMuleManager.binHoldings(dir, bot.getId(),
                BotStorageMuleManager::muleFree, BotStorageMuleManager.Member::scrollsHeld);
        Map<Integer, Integer> totals = new HashMap<>();
        bins.values().forEach(held -> held.forEach((sid, q) -> totals.merge(sid, q, Integer::sum)));
        List<Stock> useful = new ArrayList<>();
        BotOfferManager.ScrollRules rules = BotOfferManager.rules();
        totals.forEach((sid, q) -> {
            if (rules.offerable(sid) && BotOfferManager.scrollUsefulTo(bot, sid)) {
                useful.add(new Stock(sid, kindOf(sid), q, ev(bot, rules, sid)));
            }
        });
        Map<Integer, Integer> want = wanted(useful, roomByKind(bot));
        if (want.isEmpty()) {
            return;
        }
        Map<Integer, Map<Integer, Integer>> pickups = BotStorageMuleManager.assignPickups(bins, want);
        Inventory use = bot.getInventory(InventoryType.USE);
        if (use == null || use.getNumFreeSlot() <= want.size() || (bot.getMapId() != BotWorldGraph.FREE_MARKET
                && BotAutopilotManager.routeForBot(bot, bot.getMapId(), BotWorldGraph.FREE_MARKET,
                BotAutopilotManager.MAX_TRAVEL_HOPS, BotAutopilotManager.travelOptions(bot, false)) == null)) {
            return; // no room for them, or no way to the market from here
        }
        Map<Integer, Integer> need = new HashMap<>();
        want.forEach((sid, q) -> need.put(sid, bot.getItemQuantity(sid, false) + q));
        entry.scrollPickupActive = true;
        entry.scrollPickupProgress.begin(now);
        if (!BotStorageMuleManager.startPickup(entry, bot.getId(), pickups, need)) {
            clear(entry); // a mule got taken meanwhile
            return;
        }
        log.info("scroll routing: {} fetches {} from bins {}", bot.getName(), want, pickups.keySet());
        BotManager.getInstance().botReply(entry, BotManager.randomReply(List.of(
                "my storage guys have some scrolls for my gear, grabbing them",
                "picking up scrolls at the FM, brb",
                "gonna grab some scrolls a buddy left for me")));
    }

    private static double ev(Character bot, BotOfferManager.ScrollRules rules, int sid) {
        Map<String, Integer> st = rules.stats(sid);
        if (st == null) {
            return 0;
        }
        return BotScrollManager.effectiveSuccessPct(st.getOrDefault("success", 0)) / 100.0
                * BotScrollManager.offenseValueFromStats(bot, st);
    }

    static boolean tick(BotEntry entry, Character bot, boolean runAiTick) {
        if (!active(entry)) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (entry.scrollPickupProgress.stalled(now, STALL_MS)) {
            finish(entry, bot, "couldn't make it to the FM for those scrolls, later");
            return false;
        }
        switch (BotStorageMuleManager.tickPickup(entry, bot, entry.scrollPickupProgress, now, runAiTick)) {
            case TRAVELING, COLLECTING -> {
                return true;
            }
            case TRAVEL_YIELDED -> {
                return false;
            }
            case GOT_ALL -> {
                finish(entry, bot, "got the scrolls, gonna put them to use");
                return false;
            }
            default -> {
                finish(entry, bot, "my storage guys were short on scrolls, oh well");
                return false;
            }
        }
    }

    private static void finish(BotEntry entry, Character bot, String say) {
        clear(entry);
        entry.scrollPickupCheckAtMs = System.currentTimeMillis() + AFTER_PICKUP_MS
                + ThreadLocalRandom.current().nextLong(CHECK_EVERY_MS);
        if (say != null) {
            BotManager.getInstance().botReply(entry, say);
        }
    }

    static void clear(BotEntry entry) {
        if (entry.scrollPickupActive && entry.bot != null) {
            BotStorageMuleManager.releasePickups(entry, entry.bot.getId());
            BotTravelManager.clearMoveTargetPin(entry);
        }
        entry.scrollPickupActive = false;
        entry.scrollPickupProgress.clear();
    }
}
