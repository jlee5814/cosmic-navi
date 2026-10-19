package server.bots;

import client.Character;
import constants.game.ExpTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.life.Monster;

import java.awt.Point;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Stage 1 of the learned grind target scorer (docs/bot/learned-grind-target.md): picks among the rule
 * ranking, sometimes exploring a near equal alternative, and logs every committed pick with its candidates
 * and, once the next pick closes it, its outcome. One JSON line per pick under {@code logs/bot-decisions/}.
 */
final class BotGrindTargetLog {
    private static final Logger log = LoggerFactory.getLogger(BotGrindTargetLog.class);
    private static final Path DIR = Path.of("logs", "bot-decisions");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int MAX_CANDIDATES = 8;
    private static final int MAX_ALTERNATIVES = 2;
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bot-decision-log");
        t.setDaemon(true);
        return t;
    });

    // Stage 2 readiness (docs/bot/learned-grind-target.md): enough picks, on enough maps, with enough explored,
    // before training is worth running. Counted on the writer thread: the scan of earlier days is queued first.
    static final int READY_PICKS = 20_000;
    static final int READY_MAPS = 10;
    static final int READY_EXPLORED = 300;
    private static final Pattern MAP = Pattern.compile("\"map\":(\\d+)");
    private static final Pattern PICK = Pattern.compile("\"pick\":(\\d+)");
    private static final AtomicLong picks = new AtomicLong();
    private static final AtomicLong explored = new AtomicLong();
    private static final Set<Integer> maps = ConcurrentHashMap.newKeySet();

    static {
        WRITER.execute(BotGrindTargetLog::countEarlierLogs);
    }

    /**
     * A pick: the target, the ranking it came from, its index there and the probability it had; which arm chose
     * it ({@code model} or {@code rule}) and the model version, and the probability of every index the policy
     * could have taken ({@code support}), logged so training can weight a model arm pick as honestly as a rule
     * one.
     */
    record Pick(Monster target, List<BotCombatManager.ScoredGrindTarget> ranking, int index, double probability,
                String arm, String modelVersion, java.util.Map<Integer, Double> support) {
        Pick(Monster target, List<BotCombatManager.ScoredGrindTarget> ranking, int index, double probability) {
            this(target, ranking, index, probability, "rule", "", java.util.Map.of(index, probability));
        }
    }

    /** A committed pick waiting for its outcome; closed by the next commit. */
    record OpenPick(Monster target, long atMs, String context, int mobHp, int level, int exp, int hp) {
    }

    private BotGrindTargetLog() {
    }

    /**
     * Picks from the rule ranking. A fresh pick (no live target) takes, with probability
     * {@code GRIND_TARGET_EXPLORE}, one of the next two candidates whose cost is within
     * {@code GRIND_TARGET_EXPLORE_BAND_MS} of the best; otherwise the rule's first. Only candidates priced on
     * the nav graph explore: off the graph the scores are rule points, not milliseconds. Null when nothing ranks.
     */
    static Pick choose(List<BotCombatManager.ScoredGrindTarget> ranking, boolean freshPick) {
        return choose(null, null, ranking, freshPick);
    }

    /**
     * As {@link #choose(List, boolean)}, but a bot in the model arm ({@link BotGrindTargetModel#inModelArm}) lets
     * the served model choose among the same indices: its best with {@code 1 - GRIND_TARGET_EXPLORE}, each other
     * with an equal share of the rest. So the model only reorders what the rule allowed on a fresh pick, it keeps
     * exploring, and the trainer can still judge every alternative. Not a fresh pick, or nothing in band: the
     * rule's first, in either arm.
     */
    static Pick choose(BotEntry entry, Character bot, List<BotCombatManager.ScoredGrindTarget> ranking,
                       boolean freshPick) {
        if (ranking.isEmpty()) {
            return null;
        }
        long best = ranking.getFirst().graphCost();
        boolean onGraph = ranking.getFirst().regionId() >= 0;
        List<Integer> alternatives = new ArrayList<>();
        for (int i = 1; onGraph && i < ranking.size() && alternatives.size() < MAX_ALTERNATIVES; i++) {
            if (ranking.get(i).graphCost() <= best + BotCombatManager.cfg.GRIND_TARGET_EXPLORE_BAND_MS) {
                alternatives.add(i);
            }
        }
        double explore = freshPick && !alternatives.isEmpty() ? BotCombatManager.cfg.GRIND_TARGET_EXPLORE : 0.0;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        BotGrindTargetModel.Loaded model = BotGrindTargetModel.current();
        if (model != null && entry != null && bot != null && freshPick && !alternatives.isEmpty()
                && BotGrindTargetModel.inModelArm(bot.getId(), BotCombatManager.cfg.GRIND_TARGET_MODEL_SHARE)) {
            List<Integer> allowed = new ArrayList<>(alternatives.size() + 1);
            allowed.add(0);
            allowed.addAll(alternatives);
            int bestIndex = 0;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int i : allowed) {
                double score = model.scorer().score(BotGrindTargetModel.features(entry, bot, ranking, i));
                if (score > bestScore + 1e-9) { // ties keep the rule's order
                    bestIndex = i;
                    bestScore = score;
                }
            }
            java.util.Map<Integer, Double> support = new java.util.TreeMap<>();
            for (int i : allowed) {
                support.put(i, i == bestIndex ? 1.0 - explore : explore / alternatives.size());
            }
            int index = bestIndex;
            if (explore > 0 && rnd.nextDouble() < explore) {
                List<Integer> others = new ArrayList<>(allowed);
                others.remove(Integer.valueOf(bestIndex));
                index = others.get(rnd.nextInt(others.size()));
            }
            return new Pick(ranking.get(index).monster(), ranking, index, support.get(index), "model",
                    model.version(), support);
        }
        java.util.Map<Integer, Double> support = new java.util.TreeMap<>();
        support.put(0, 1.0 - explore);
        for (int i : alternatives) {
            if (explore > 0) {
                support.put(i, explore / alternatives.size());
            }
        }
        if (explore > 0 && rnd.nextDouble() < explore) {
            int index = alternatives.get(rnd.nextInt(alternatives.size()));
            return new Pick(ranking.get(index).monster(), ranking, index, explore / alternatives.size(), "rule", "",
                    support);
        }
        return new Pick(ranking.getFirst().monster(), ranking, 0, 1.0 - explore, "rule", "", support);
    }

    /** The bot adopted {@code pick}: close the previous pick with its outcome, then open this one. */
    static void onCommit(BotEntry entry, Character bot, Pick pick, long now) {
        if (pick == null || bot == null || bot.getMap() == null) {
            return;
        }
        OpenPick open = entry.openGrindPick;
        if (open != null && open.target() == pick.target()) {
            return; // re-confirmed the same mob: still one pick
        }
        if (open != null) {
            write(close(open, bot, now));
        }
        Monster target = pick.target();
        entry.openGrindPick = new OpenPick(target, now, context(entry, bot, pick), target.getHp(),
                bot.getLevel(), bot.getExp(), bot.getHp());
    }

    private static String context(BotEntry entry, Character bot, Pick pick) {
        Point pos = bot.getPosition();
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"t\":").append(System.currentTimeMillis())
                .append(",\"bot\":").append(bot.getId())
                .append(",\"map\":").append(bot.getMap().getId())
                .append(",\"lvl\":").append(bot.getLevel())
                .append(",\"job\":").append(bot.getJob().getId())
                .append(",\"weapon\":\"").append(BotAttackExecutionProvider.getEquippedWeaponType(bot)).append('"')
                .append(",\"hp\":").append(ratio(bot.getHp(), bot.getCurrentMaxHp()))
                .append(",\"mp\":").append(ratio(bot.getMp(), bot.getCurrentMaxMp()))
                .append(",\"x\":").append(pos.x).append(",\"y\":").append(pos.y)
                .append(",\"rope\":").append(entry.climbing)
                .append(",\"cands\":[");
        List<BotCombatManager.ScoredGrindTarget> ranking = pick.ranking();
        for (int i = 0; i < Math.min(MAX_CANDIDATES, ranking.size()); i++) {
            BotCombatManager.ScoredGrindTarget c = ranking.get(i);
            Monster m = c.monster();
            Point mp = m.getPosition();
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"mob\":").append(m.getId())
                    .append(",\"mobLvl\":").append(m.getLevel())
                    .append(",\"mobHp\":").append(ratio(m.getHp(), m.getMaxHp()))
                    .append(",\"dx\":").append(mp.x - pos.x).append(",\"dy\":").append(mp.y - pos.y)
                    .append(",\"route\":").append(c.graphCost())
                    .append(",\"local\":").append(c.localScore())
                    .append(",\"region\":").append(c.regionId())
                    .append(",\"crowd\":").append(c.mobCount()).append('}');
        }
        sb.append("],\"pick\":").append(pick.index())
                .append(",\"p\":").append(ratio(pick.probability()))
                .append(",\"arm\":\"").append(pick.arm()).append('"');
        if (!pick.modelVersion().isEmpty()) {
            sb.append(",\"mv\":\"").append(pick.modelVersion()).append('"');
        }
        sb.append(",\"sup\":{");
        boolean first = true;
        for (var e : pick.support().entrySet()) {
            sb.append(first ? "" : ",").append('"').append(e.getKey()).append("\":").append(ratio(e.getValue()));
            first = false;
        }
        return sb.append('}').toString();
    }

    private static String close(OpenPick open, Character bot, long now) {
        Monster target = open.target();
        int mobHpNow = target.isAlive() ? target.getHp() : 0;
        return open.context()
                + ",\"out\":{\"holdMs\":" + (now - open.atMs())
                + ",\"exp\":" + expGained(open.level(), open.exp(), bot.getLevel(), bot.getExp())
                + ",\"died\":" + !target.isAlive()
                + ",\"mobHpDrop\":" + ratio(open.mobHp() - mobHpNow, target.getMaxHp())
                + ",\"hpLost\":" + Math.max(0, open.hp() - bot.getHp())
                + "}}";
    }

    /** EXP earned between two (level, exp) snapshots, level ups included. */
    static long expGained(int fromLevel, int fromExp, int toLevel, int toExp) {
        if (toLevel < fromLevel) {
            return 0;
        }
        long gained = toExp - (long) fromExp;
        for (int l = fromLevel; l < toLevel; l++) {
            gained += ExpTable.getExpNeededForLevel(l);
        }
        return Math.max(0, gained);
    }

    private static String ratio(int part, int whole) {
        return whole <= 0 ? "0" : ratio(Math.max(0, part) / (double) whole);
    }

    private static String ratio(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    /** {@code {"picks","explored","maps","need":{...},"ready"}}: how close the log is to worth training on. */
    static String readinessJson() {
        long p = picks.get();
        long e = explored.get();
        int m = maps.size();
        boolean ready = p >= READY_PICKS && m >= READY_MAPS && e >= READY_EXPLORED;
        return "{\"picks\":" + p + ",\"explored\":" + e + ",\"maps\":" + m
                + ",\"need\":{\"picks\":" + READY_PICKS + ",\"maps\":" + READY_MAPS + ",\"explored\":" + READY_EXPLORED
                + "},\"ready\":" + ready + "}";
    }

    static void count(String line) {
        Matcher map = MAP.matcher(line);
        Matcher pick = PICK.matcher(line);
        if (!map.find() || !pick.find()) {
            return;
        }
        picks.incrementAndGet();
        maps.add(Integer.parseInt(map.group(1)));
        if (!"0".equals(pick.group(1))) {
            explored.incrementAndGet();
        }
    }

    private static void countEarlierLogs() {
        if (!Files.isDirectory(DIR)) {
            return;
        }
        try (Stream<Path> files = Files.list(DIR)) {
            for (Path f : (Iterable<Path>) files.filter(f -> f.getFileName().toString().endsWith(".jsonl"))::iterator) {
                try (Stream<String> lines = Files.lines(f)) {
                    lines.forEach(BotGrindTargetLog::count);
                }
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            log.warn("bot decision log count failed: {}", e.toString());
        }
    }

    private static void write(String line) {
        WRITER.execute(() -> {
            count(line);
            try {
                Files.createDirectories(DIR);
                Files.writeString(DIR.resolve("grind-target-" + LocalDate.now().format(DAY) + ".jsonl"),
                        line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.warn("bot decision log write failed: {}", e.toString());
            }
        });
    }
}
