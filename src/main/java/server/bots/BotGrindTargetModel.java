package server.bots;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.life.Monster;

import java.awt.Point;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stage 3 of the learned grind target scorer (docs/bot/learned-grind-target.md): the trained model, served.
 *
 * <p>Loads the trainer's JSON ({@code tools/learned/train_grind_target.py}, linear or trees) from
 * {@link #path}, and reloads it within a minute of the file changing, so promoting a retrained model needs no
 * restart. Only a model whose {@code passes_gate} is true is served. The feature vector is the trainer's
 * {@code features()}, from the same rounded numbers the decision log writes, so the served score is the one
 * the gate judged.
 *
 * <p>The fleet is split by bot id ({@link #inModelArm}): bots in the model arm let the model choose among the
 * picks the rule allows; the rest keep the rule. {@code GRIND_TARGET_MODEL_SHARE} sets the split (0 turns the
 * model off without a deploy). No model, a model that failed its gate, or a file that won't parse: every bot
 * uses the rule.
 */
final class BotGrindTargetModel {
    private static final Logger log = LoggerFactory.getLogger(BotGrindTargetModel.class);
    static final List<String> FEATURES = List.of(
            "rank", "route_s", "local_k", "crowd", "lvl_gap", "mob_hp", "adx_100", "dy_100", "rope", "bot_hp");
    static final long RELOAD_CHECK_MS = 60_000L;

    static Path path = Path.of("models", "grind-target-model.json");

    /** A loaded model: its version and the score function. */
    record Loaded(String kind, String version, Scorer scorer) {
    }

    @FunctionalInterface
    interface Scorer {
        double score(double[] x);
    }

    private static volatile Loaded current;
    private static volatile long loadedMtime = Long.MIN_VALUE;
    private static final AtomicBoolean started = new AtomicBoolean();

    private BotGrindTargetModel() {
    }

    static void ensureStarted() {
        if (started.compareAndSet(false, true)) {
            reloadIfChanged();
            TimerManager.getInstance().register(BotGrindTargetModel::reloadIfChanged, RELOAD_CHECK_MS, RELOAD_CHECK_MS);
        }
    }

    static Loaded current() {
        return current;
    }

    /** Test seam and reload path: serve {@code model}, or none. */
    static void set(Loaded model) {
        current = model;
    }

    static void reloadIfChanged() {
        try {
            if (!Files.isRegularFile(path)) {
                if (current != null) {
                    log.info("learned grind target: {} is gone; every bot uses the rule", path);
                }
                current = null;
                loadedMtime = Long.MIN_VALUE;
                return;
            }
            long mtime = Files.getLastModifiedTime(path).toMillis();
            if (mtime == loadedMtime) {
                return;
            }
            loadedMtime = mtime;
            Loaded model = parse(Files.readString(path));
            current = model;
            log.info("learned grind target: {}", model == null
                    ? path + " did not pass its gate; every bot uses the rule"
                    : "serving " + model.kind() + " model " + model.version() + " from " + path);
        } catch (IOException | RuntimeException e) {
            current = null;
            log.warn("learned grind target: couldn't load {}: {}; every bot uses the rule", path, e.toString());
        }
    }

    /** The model in the trainer's JSON, or null when it didn't pass its gate. Throws on a malformed file. */
    @SuppressWarnings("unchecked")
    static Loaded parse(String json) {
        Map<String, Object> m = (Map<String, Object>) BotJson.parse(json);
        if (!Boolean.TRUE.equals(m.get("passes_gate"))) {
            return null;
        }
        if (!FEATURES.equals(m.get("features"))) {
            throw new IllegalArgumentException("features " + m.get("features") + " are not " + FEATURES);
        }
        String version = String.valueOf(m.getOrDefault("version", "unversioned"));
        if ("trees".equals(m.get("kind"))) {
            double base = num(m.get("base"));
            double lr = num(m.get("lr"));
            List<Object> raw = (List<Object>) m.get("trees");
            Node[] trees = new Node[raw.size()];
            for (int t = 0; t < trees.length; t++) {
                trees[t] = node((Map<String, Object>) raw.get(t));
            }
            return new Loaded("trees", version, x -> {
                double sum = 0.0;
                for (Node tree : trees) {
                    sum += tree.value(x);
                }
                return base + lr * sum;
            });
        }
        double[] w = nums((List<Object>) m.get("weights"));
        double[] mean = nums((List<Object>) m.get("mean"));
        double[] std = nums((List<Object>) m.get("std"));
        double bias = num(m.get("bias"));
        return new Loaded("linear", version, x -> {
            double s = bias;
            for (int j = 0; j < w.length; j++) {
                s += w[j] * (x[j] - mean[j]) / std[j];
            }
            return s;
        });
    }

    /** One tree node: a leaf value, or a split that sends {@code x[feature] <= threshold} left. */
    record Node(int feature, double threshold, double leaf, Node left, Node right) {
        double value(double[] x) {
            Node n = this;
            while (n.left != null) {
                n = x[n.feature] <= n.threshold ? n.left : n.right;
            }
            return n.leaf;
        }
    }

    @SuppressWarnings("unchecked")
    private static Node node(Map<String, Object> m) {
        if (m.containsKey("v")) {
            return new Node(-1, 0.0, num(m.get("v")), null, null);
        }
        return new Node((int) num(m.get("f")), num(m.get("t")), 0.0,
                node((Map<String, Object>) m.get("l")), node((Map<String, Object>) m.get("r")));
    }

    private static double num(Object o) {
        if (!(o instanceof Double d)) {
            throw new IllegalArgumentException("expected a number, got " + o);
        }
        return d;
    }

    private static double[] nums(List<Object> list) {
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = num(list.get(i));
        }
        return out;
    }

    /**
     * Candidate {@code index} of the rule ranking as the trainer's feature vector. The log rounds HP ratios to
     * three places and the trainer learned from those, so the same rounding happens here.
     */
    static double[] features(BotEntry entry, Character bot, List<BotCombatManager.ScoredGrindTarget> ranking,
                             int index) {
        BotCombatManager.ScoredGrindTarget c = ranking.get(index);
        Monster m = c.monster();
        Point pos = bot.getPosition();
        Point mp = m.getPosition();
        return new double[]{
                index,
                Math.min(c.graphCost(), 60_000L) / 1000.0,
                Math.max(-60_000L, Math.min(c.localScore(), 60_000L)) / 1000.0,
                c.mobCount(),
                m.getLevel() - bot.getLevel(),
                round3(ratio(m.getHp(), m.getMaxHp())),
                Math.abs(mp.x - pos.x) / 100.0,
                (mp.y - pos.y) / 100.0,
                entry.climbing ? 1.0 : 0.0,
                round3(ratio(bot.getHp(), bot.getCurrentMaxHp())),
        };
    }

    private static double ratio(int part, int whole) {
        return whole <= 0 ? 0.0 : Math.max(0, part) / (double) whole;
    }

    /** As the log writes it ({@code %.3f}), so served features equal logged ones. */
    static double round3(double v) {
        return Double.parseDouble(String.format(Locale.ROOT, "%.3f", v));
    }

    /** {@code {"kind","version","share"}} for the served model, or null when every bot uses the rule. */
    static String statusJson(double share) {
        Loaded m = current;
        return m == null ? "null" : "{\"kind\":\"" + m.kind() + "\",\"version\":\"" + m.version()
                + "\",\"share\":" + share + "}";
    }

    /** Whether this bot is in the model arm of the split: a stable hash of its id against the share. */
    static boolean inModelArm(int botId, double share) {
        if (share <= 0) {
            return false;
        }
        if (share >= 1) {
            return true;
        }
        int bucket = Math.floorMod(botId * 0x9E3779B9, 1000);
        return bucket < share * 1000;
    }
}
