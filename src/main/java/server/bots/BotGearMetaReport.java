package server.bots;

import client.Job;
import client.inventory.Equip;
import server.ItemInformationProvider;
import tools.Pair;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which gear the bots' own gear score ({@link BotScrollManager#potentialValue}, here per job with catalog scroll
 * headroom only) ranks best in each slot, for each class, level by level: the items that hold the top spot
 * across the most levels are the ones the score says are long lived. A check of the scorer against the meta
 * players settled on (Zakum Helmet, Pink Adventurer Cape, Snowshoes, Work Gloves). Read only; served at
 * {@code /api/gearmeta}. Clean catalog stats, any stat requirement met, obtainable items only (a drop, a shop or
 * an NPC recipe).
 */
final class BotGearMetaReport {
    private BotGearMetaReport() {}

    /** One job per class: its weights and wear rules stand for the class. */
    static final Map<String, Integer> CLASSES = new LinkedHashMap<>();
    static {
        CLASSES.put("Warrior", 110);
        CLASSES.put("Mage", 210);
        CLASSES.put("Bowman", 310);
        CLASSES.put("Thief", 410);
        CLASSES.put("Pirate", 510);
    }

    /** Armor and accessory kinds ({@code itemId / 10000}). */
    static final int[] KINDS = {100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 112};
    private static final int ANY_STAT = 30_000;

    record Candidate(int itemId, int reqLevel, double score, String source) {}

    /** A run of levels an item ranks best in its slot. */
    record Reign(int itemId, int from, int to, double score, String source) {}

    /** The best ranked item per level, compressed into reigns: pure, so it can be tested on its own. Ties go to
     *  the lower level requirement, then the lower id. */
    static List<Reign> reigns(List<Candidate> cands, int from, int to, int step) {
        List<Reign> out = new ArrayList<>();
        Candidate prev = null;
        int start = from;
        for (int level = from; level <= to; level += step) {
            Candidate best = null;
            for (Candidate c : cands) {
                if (c.reqLevel() <= level && (best == null || c.score() > best.score()
                        || (c.score() == best.score() && (c.reqLevel() < best.reqLevel()
                        || (c.reqLevel() == best.reqLevel() && c.itemId() < best.itemId()))))) {
                    best = c;
                }
            }
            if (best != prev) {
                if (prev != null) {
                    out.add(new Reign(prev.itemId(), start, level - step, prev.score(), prev.source()));
                }
                prev = best;
                start = level;
            }
        }
        if (prev != null) {
            out.add(new Reign(prev.itemId(), start, to - (to - from) % step, prev.score(), prev.source()));
        }
        return out;
    }

    private static String source(ItemInformationProvider ii, int id) {
        if (BotScrollManager.bestDropChance(id) > 0) {
            return "drop";
        }
        if (BotScrollManager.marketBuyPriceMeso(id) > 0) {
            return "shop";
        }
        return BotCraftManager.craftable(id) ? "craft" : null;
    }

    /** Every obtainable, non cash item of this kind the job can wear, scored. */
    static List<Candidate> candidates(ItemInformationProvider ii, int jobId, int kind) {
        Job job = Job.getById(jobId);
        List<Candidate> out = new ArrayList<>();
        for (Pair<Integer, String> p : ii.getAllItems()) {
            int id = p.getLeft();
            if (id / 10000 != kind || ii.isCash(id)) {
                continue;
            }
            String src = source(ii, id);
            if (src == null || !(ii.getEquipById(id) instanceof Equip clean)
                    || !ii.meetsEquipRequirements(clean, job, 200, ANY_STAT, ANY_STAT, ANY_STAT, ANY_STAT, Short.MAX_VALUE)) {
                continue;
            }
            Integer req = ii.getEquipLevelReq(id);
            out.add(new Candidate(id, req == null ? 0 : req, BotScrollManager.potentialValue(jobId, ii, clean), src));
        }
        return out;
    }

    /** The report as JSON: per class and kind, the reigns from {@code from} to {@code to}; then, for each watched
     *  item, its source and how many levels it ranks best for each class. */
    static String json(int from, int to, int step, List<Integer> watch) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Map<Integer, Map<String, Integer>> watchLevels = new LinkedHashMap<>();
        watch.forEach(id -> watchLevels.put(id, new LinkedHashMap<>()));
        List<String> classes = new ArrayList<>();
        for (Map.Entry<String, Integer> cls : CLASSES.entrySet()) {
            List<String> kinds = new ArrayList<>();
            for (int kind : KINDS) {
                List<String> rows = new ArrayList<>();
                for (Reign r : reigns(candidates(ii, cls.getValue(), kind), from, to, step)) {
                    int levels = r.to() - r.from() + step;
                    if (watchLevels.containsKey(r.itemId())) {
                        watchLevels.get(r.itemId()).merge(cls.getKey(), levels, Integer::sum);
                    }
                    rows.add("{\"id\":" + r.itemId() + ",\"n\":" + str(ii.getName(r.itemId())) + ",\"from\":" + r.from()
                            + ",\"to\":" + r.to() + ",\"levels\":" + levels + ",\"score\":" + Math.round(r.score() * 10) / 10.0
                            + ",\"src\":" + str(r.source()) + "}");
                }
                kinds.add("{\"kind\":" + kind + ",\"reigns\":[" + String.join(",", rows) + "]}");
            }
            classes.add("{\"class\":" + str(cls.getKey()) + ",\"job\":" + cls.getValue() + ",\"kinds\":["
                    + String.join(",", kinds) + "]}");
        }
        List<String> watched = new ArrayList<>();
        watchLevels.forEach((id, byClass) -> {
            List<String> per = new ArrayList<>();
            byClass.forEach((c, n) -> per.add(str(c) + ":" + n));
            Integer req = ii.getEquipLevelReq(id);
            watched.add("{\"id\":" + id + ",\"n\":" + str(ii.getName(id)) + ",\"reqLevel\":" + (req == null ? 0 : req)
                    + ",\"src\":" + str(source(ii, id)) + ",\"bestLevels\":{" + String.join(",", per) + "}}");
        });
        return "{\"from\":" + from + ",\"to\":" + to + ",\"step\":" + step + ",\"classes\":[" + String.join(",", classes)
                + "],\"watch\":[" + String.join(",", watched) + "]}";
    }

    private static String str(String s) {
        if (s == null) {
            return "null";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
