package server.bots;

import client.Character;
import client.inventory.Equip;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import server.life.LifeFactory;
import server.life.Monster;
import tools.Pair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The gear that pays back fastest in leveling, given how strong the bot is now. For every obtainable piece it can
 * wear today: its gain over the best it owns for the slot (the grind planner's {@link
 * BotGrindAdvisor#catalogAcquireGain}, an overall against top plus pants), the cheapest way to get one ({@link
 * BotScrollManager#cheapestAcquisition}: a shop's price, its own farming effort on the best dropper, or an NPC
 * craft, in hours at the effort anchor), and the gear detour verdict ({@link BotGearPayback#judge}): the
 * experience per hour it adds on the bot's grind map, the hours of grinding with it that win back the detour,
 * and the hours it will be worn before a better piece can be. Ranked by payback. A boss drop is priced by this bot's solo kill time, so it pays
 * back sooner as the bot grows. Read only; served at {@code /api/gearplan}.
 */
final class BotGearPlanReport {
    private BotGearPlanReport() {}

    /** A piece the bot could go get: how much it gains, what it costs, the experience per hour it adds on the
     *  grind map and the hours of grinding with it that repay the detour (infinite when it adds none). */
    record Pick(int itemId, int reqLevel, double gain, BotScrollManager.Acquisition how, BotGearPayback.Verdict verdict) {
        double paybackHours() {
            return verdict.paybackHours();
        }
    }

    /** Fastest payback first, then the bigger gain. */
    static final Comparator<Pick> BEST_FIRST = Comparator.comparingDouble(Pick::paybackHours)
            .thenComparing(Comparator.comparingDouble(Pick::gain).reversed());

    /** The bot's grind map as the grind planner sees it (its destination, else where it stands), or null. */
    static BotGrindPlanner.MobCandidate grindMap(BotEntry entry, Character bot) {
        int map = entry != null && entry.autopilotMapId != -1 ? entry.autopilotMapId : bot.getMapId();
        List<BotGrindPlanner.MobCandidate> cs = BotGrindAdvisor.candidatesFor(entry, bot, m -> m == map);
        return cs.isEmpty() ? null : cs.get(0);
    }

    /** Every upgrade the bot can wear now and get some way, by slot. */
    static Map<Short, List<Pick>> picksBySlot(BotEntry entry, Character bot, ItemInformationProvider ii,
                                              BotGrindPlanner.MobCandidate grind) {
        Map<Short, List<Pick>> bySlot = new TreeMap<>();
        Map<Short, Double> bar = new java.util.HashMap<>();
        for (Pair<Integer, String> p : ii.getAllItems()) {
            int id = p.getLeft();
            Equip clean = wearableNow(bot, ii, id);
            if (clean == null) {
                continue;
            }
            Integer req = ii.getEquipLevelReq(id);
            Short slot = BotScrollManager.primarySlot(ii, id);
            double gain = BotGrindAdvisor.catalogAcquireGain(bot, ii, id, bar);
            if (gain <= 0) {
                continue;
            }
            BotScrollManager.Acquisition how = BotScrollManager.cheapestAcquisition(bot, id);
            if (Double.isFinite(how.meso())) {
                bySlot.computeIfAbsent(slot, s -> new ArrayList<>()).add(new Pick(id, req == null ? 0 : req, gain, how,
                        BotGearPayback.judge(bot, ii, clean, gain, how.hours(), grind)));
            }
        }
        bySlot.values().forEach(l -> l.sort(BEST_FIRST));
        return bySlot;
    }

    /** The catalog copy of {@code id} when the bot can wear it today (level, job, stats, preferred weapon type),
     *  else null. Cash items and non-equips are null. */
    static Equip wearableNow(Character bot, ItemInformationProvider ii, int id) {
        if (id < 1000000 || id >= 2000000 || ii.isCash(id)) {
            return null;
        }
        Integer req = ii.getEquipLevelReq(id);
        Short slot = BotScrollManager.primarySlot(ii, id);
        if (slot == null || (req != null && req > bot.getLevel()) || !(ii.getEquipById(id) instanceof Equip clean)
                || !ii.canWearEquipment(bot, clean, slot)
                || (ItemConstants.isWeapon(id) && !BotEquipManager.isPreferredWeapon(bot, ii.getWeaponType(id), clean))) {
            return null;
        }
        return clean;
    }

    static String json(BotEntry entry, Character bot) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        BotGrindPlanner.MobCandidate grind = grindMap(entry, bot);
        Map<Short, List<Pick>> bySlot = picksBySlot(entry, bot, ii, grind);
        List<Pick> all = new ArrayList<>();
        List<String> slots = new ArrayList<>();
        bySlot.forEach((slot, picks) -> {
            all.addAll(picks);
            Equip worn = BotScrollManager.wornInSlot(bot, ii, slot);
            List<String> rows = new ArrayList<>();
            picks.stream().limit(3).forEach(p -> rows.add(pick(ii, p)));
            slots.add("{\"slot\":" + slot + ",\"worn\":" + (worn == null ? "null" : "{\"id\":" + worn.getItemId()
                    + ",\"n\":" + str(ii.getName(worn.getItemId())) + ",\"score\":"
                    + round(BotScrollManager.potentialValue(bot, ii, worn)) + "}") + ",\"picks\":[" + String.join(",", rows) + "]}");
        });
        all.sort(BEST_FIRST);
        List<String> top = new ArrayList<>();
        all.stream().limit(10).forEach(p -> top.add(pick(ii, p)));
        String map = grind == null ? "null" : "{\"id\":" + grind.mapId() + ",\"n\":" + str(grind.mapName())
                + ",\"mob\":" + str(grind.mobName()) + ",\"expPerHour\":"
                + Math.round(grind.exp() * BotGrindPlanner.killsPerHour(grind)) + ",\"killSeconds\":"
                + round(grind.killSeconds()) + "}";
        return "{\"id\":" + bot.getId() + ",\"name\":" + str(bot.getName()) + ",\"level\":" + bot.getLevel()
                + ",\"job\":" + (bot.getJob() == null ? 0 : bot.getJob().getId()) + ",\"grind\":" + map
                + ",\"best\":[" + String.join(",", top)
                + "],\"slots\":[" + String.join(",", slots) + "]}";
    }

    private static String pick(ItemInformationProvider ii, Pick p) {
        String via = p.how().route();
        if ("farm".equals(via)) {
            int mob = BotScrollManager.bestDropperMob(p.itemId());
            Monster m = mob == 0 ? null : LifeFactory.getMonster(mob);
            via = "farm " + (m == null ? String.valueOf(mob) : m.getName());
        }
        return "{\"id\":" + p.itemId() + ",\"n\":" + str(ii.getName(p.itemId())) + ",\"req\":" + p.reqLevel()
                + ",\"gain\":" + round(p.gain()) + ",\"via\":" + str(via) + ",\"hours\":" + Math.round(p.how().hours() * 100) / 100.0
                + ",\"expPerHour\":" + Math.round(p.verdict().expPerHourWith() - p.verdict().expPerHour())
                + ",\"payback\":" + hours(p.paybackHours()) + ",\"wear\":" + hours(p.verdict().wearHours())
                + ",\"worth\":" + p.verdict().worth() + "}";
    }

    private static String hours(double h) {
        return Double.isFinite(h) ? String.valueOf(Math.round(h * 100) / 100.0) : "null";
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static String str(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
