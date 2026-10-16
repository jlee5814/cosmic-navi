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
 * The best gear a bot can get per hour of effort, given how strong it is now: every obtainable piece it can wear
 * today, its gain over what it wears in that slot by the gear score ({@link BotScrollManager#potentialValue}), and
 * that gain over the cheapest way to get one ({@link BotScrollManager#cheapestAcquisition}: a shop's price, its own
 * farming effort on the best dropper, or an NPC craft, all in hours at the effort anchor). A boss drop is priced
 * by how long this bot alone takes to kill the boss, so it gets cheaper as the bot gets stronger. Read only;
 * served at {@code /api/gearplan}.
 */
final class BotGearPlanReport {
    private BotGearPlanReport() {}

    /** A piece the bot could go get: how much it gains and what it costs. */
    record Pick(int itemId, int reqLevel, double gain, BotScrollManager.Acquisition how) {
        double perHour() {
            return how.hours() <= 0 ? Double.POSITIVE_INFINITY : gain / how.hours();
        }
    }

    /** Best gain per hour first; a free pick (already cheap to nothing) first of all. */
    static final Comparator<Pick> BEST_FIRST = Comparator.comparingDouble(Pick::perHour).reversed()
            .thenComparing(Comparator.comparingDouble(Pick::gain).reversed());

    /** Every upgrade the bot can wear now and get some way, by slot. */
    static Map<Short, List<Pick>> picksBySlot(Character bot, ItemInformationProvider ii) {
        Map<Short, List<Pick>> bySlot = new TreeMap<>();
        Map<Short, Double> wornScore = new TreeMap<>();
        for (Pair<Integer, String> p : ii.getAllItems()) {
            int id = p.getLeft();
            if (id < 1000000 || id >= 2000000 || ii.isCash(id)) {
                continue;
            }
            Integer req = ii.getEquipLevelReq(id);
            Short slot = BotScrollManager.primarySlot(ii, id);
            if (slot == null || (req != null && req > bot.getLevel()) || !(ii.getEquipById(id) instanceof Equip clean)
                    || !ii.canWearEquipment(bot, clean, slot)
                    || (ItemConstants.isWeapon(id) && !BotEquipManager.isPreferredWeapon(bot, ii.getWeaponType(id), clean))) {
                continue;
            }
            double worn = wornScore.computeIfAbsent(slot, s -> {
                Equip w = BotScrollManager.wornInSlot(bot, ii, s);
                return w == null ? 0.0 : BotScrollManager.potentialValue(bot, ii, w);
            });
            double gain = BotScrollManager.potentialValue(bot, ii, clean) - worn;
            if (gain <= 0) {
                continue;
            }
            BotScrollManager.Acquisition how = BotScrollManager.cheapestAcquisition(bot, id);
            if (Double.isFinite(how.meso())) {
                bySlot.computeIfAbsent(slot, s -> new ArrayList<>()).add(new Pick(id, req == null ? 0 : req, gain, how));
            }
        }
        bySlot.values().forEach(l -> l.sort(BEST_FIRST));
        return bySlot;
    }

    static String json(Character bot) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Map<Short, List<Pick>> bySlot = picksBySlot(bot, ii);
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
        return "{\"id\":" + bot.getId() + ",\"name\":" + str(bot.getName()) + ",\"level\":" + bot.getLevel()
                + ",\"job\":" + (bot.getJob() == null ? 0 : bot.getJob().getId()) + ",\"best\":[" + String.join(",", top)
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
                + ",\"perHour\":" + (Double.isFinite(p.perHour()) ? round(p.perHour()) : "null") + "}";
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static String str(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
