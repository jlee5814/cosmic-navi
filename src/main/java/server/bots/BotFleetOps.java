package server.bots;

import client.Character;
import constants.game.ExpTable;
import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.maps.MapFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Navi fleet ops: what the owner's bots are doing, by whisper, and a whisper first when one is stuck.
 *
 * <p>A sampler records every bot's map, position and total EXP once a minute and keeps 90 minutes of
 * history. From it: a bot is <b>frozen</b> when it means to grind, travel or run an errand but has not
 * moved or earned EXP for {@link #FROZEN_ALERT_MS}; a bot is <b>looping</b> when it starts an errand to
 * the same map {@link #LOOP_REPEATS} times within {@link #LOOP_WINDOW_MS} (SipsBuddy26 rode to New Leaf
 * City every ten minutes all night); a bot <b>earns nothing</b> when it stays busy for
 * {@link #NO_EXP_ALERT_MS} without gaining EXP, wherever it goes. Each one whispers the bot's registered
 * owner once per incident. Samples are saved to {@link #store} every minute and reloaded at start, so a
 * deploy neither blanks the history nor repeats a whisper.
 *
 * <p>Commands, whispered to any bot the speaker owns: {@code navi fleet}, {@code navi why <bot>},
 * {@code navi rescue <bot>} (a town return scroll, the legal way out, then back to autopilot) and
 * {@code navi park <bot>} (the same park the cap check uses). {@code <bot>} is a full name or the
 * number at its end ({@code 26} for SipsBuddy26). Only bots registered to the speaker are listed or
 * touched.
 */
final class BotFleetOps {
    private static final Logger log = LoggerFactory.getLogger(BotFleetOps.class);

    static final long SAMPLE_MS = 60_000L;
    static final int KEEP_SAMPLES = 90;
    static final long FROZEN_ALERT_MS = 10 * 60_000L;
    static final long FROZEN_LIST_MS = 5 * 60_000L;   // shown by navi fleet before it is worth an alert
    static final int STILL_PX = 8;
    static final int LOOP_REPEATS = 3;
    static final long LOOP_WINDOW_MS = 45 * 60_000L;
    static final long LOOP_ALERT_COOLDOWN_MS = 2 * 60 * 60_000L;
    static final long RATE_WINDOW_MS = 60 * 60_000L;
    static final long MIN_RATE_SPAN_MS = 10 * 60_000L;
    static final long NO_EXP_ALERT_MS = 30 * 60_000L;
    static final long NO_EXP_LIST_MS = 15 * 60_000L;  // shown by navi fleet before it is worth an alert
    static final long FORGET_OFFLINE_MS = 15 * 60_000L;
    static final int MAX_LINE = 90;

    /** Seam: where samples survive a restart (the server-cache volume outlives a rebuild). */
    static Path store = Path.of("cache", "fleet-ops", "samples.tsv");

    private static final Pattern FLEET = Pattern.compile("^(?:fleet|status|bots)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION = Pattern.compile("^(why|rescue|unstick|park)\\s+(\\S+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HELP = Pattern.compile("^(?:help|\\?|commands)?$", Pattern.CASE_INSENSITIVE);
    static final String HELP_LINE = "navi can do: email, fleet, why <bot>, rescue <bot>, park <bot>";

    record Sample(long t, int mapId, int x, int y, long totalExp, boolean busy, int errandMapId) {}

    static final class Track {
        final ArrayDeque<Sample> samples = new ArrayDeque<>();
        final ArrayDeque<long[]> errandStarts = new ArrayDeque<>(); // {startedAtMs, mapId}
        boolean frozenAlerted;
        boolean noExpAlerted;
        final Map<Integer, Long> loopAlertedAtMs = new HashMap<>();
    }

    static final Map<Integer, Track> tracks = new ConcurrentHashMap<>();
    private static final AtomicBoolean started = new AtomicBoolean();
    private static final long[] CUMULATIVE_EXP = cumulativeExp();

    private BotFleetOps() {}

    /** Start the once a minute sampler (idempotent); called when bots register, so TimerManager is up. */
    static void ensureStarted() {
        if (started.compareAndSet(false, true)) {
            load(System.currentTimeMillis());
            TimerManager.getInstance().register(BotFleetOps::sampleAllSafely, SAMPLE_MS, SAMPLE_MS);
        }
    }

    // ---- sampling and detection ------------------------------------------------------------------

    private static void sampleAllSafely() {
        long now = System.currentTimeMillis();
        for (BotEntry entry : BotManager.getInstance().allEntries()) {
            try {
                Character bot = entry.bot;
                if (bot == null || bot.getMap() == null) {
                    continue;
                }
                Track track = tracks.computeIfAbsent(bot.getId(), id -> new Track());
                record(track, sampleOf(entry, bot, now));
                List<String> alerts = alerts(track, now, bot.getName(), shortName(bot.getName()));
                if (!alerts.isEmpty()) {
                    notifyOwner(entry, bot, alerts);
                }
            } catch (RuntimeException e) {
                log.debug("fleet ops: sampling {} failed", entry.bot != null ? entry.bot.getName() : "?", e);
            }
        }
        // A bot still logging in after a restart keeps its loaded history for a while.
        tracks.entrySet().removeIf(e -> BotManager.getInstance().getEntryByBotCharId(e.getKey()) == null
                && now - lastSampleAt(e.getValue()) > FORGET_OFFLINE_MS);
        save();
    }

    private static long lastSampleAt(Track track) {
        synchronized (track) {
            Sample last = track.samples.peekLast();
            return last == null ? 0L : last.t();
        }
    }

    // ---- surviving a restart -----------------------------------------------------------------------

    /** Write every track's samples, one per line: bot id, time, map, x, y, total EXP, busy, errand map. */
    static void save() {
        StringBuilder sb = new StringBuilder();
        for (var e : tracks.entrySet()) {
            synchronized (e.getValue()) {
                for (Sample s : e.getValue().samples) {
                    sb.append(e.getKey()).append('\t').append(s.t()).append('\t').append(s.mapId()).append('\t')
                            .append(s.x()).append('\t').append(s.y()).append('\t').append(s.totalExp()).append('\t')
                            .append(s.busy() ? 1 : 0).append('\t').append(s.errandMapId()).append('\n');
                }
            }
        }
        try {
            Files.createDirectories(store.getParent());
            Path tmp = store.resolveSibling(store.getFileName() + ".tmp");
            Files.writeString(tmp, sb, StandardCharsets.UTF_8);
            Files.move(tmp, store, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            log.debug("fleet ops: saving samples failed", e);
        }
    }

    /** Reload samples younger than the history window, so a deploy doesn't blank navi fleet for ten
     *  minutes or reset a bot's stuck clock. Incidents already under way count as already whispered. */
    static void load(long now) {
        if (!Files.isRegularFile(store)) {
            return;
        }
        Map<Integer, List<Sample>> byBot = new HashMap<>();
        try {
            for (String line : Files.readAllLines(store, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t");
                if (f.length != 8) {
                    continue;
                }
                Sample s = new Sample(Long.parseLong(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]),
                        Integer.parseInt(f[4]), Long.parseLong(f[5]), "1".equals(f[6]), Integer.parseInt(f[7]));
                if (now - s.t() <= KEEP_SAMPLES * SAMPLE_MS) {
                    byBot.computeIfAbsent(Integer.parseInt(f[0]), id -> new ArrayList<>()).add(s);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.debug("fleet ops: loading samples failed", e);
            return;
        }
        for (var e : byBot.entrySet()) {
            Track track = tracks.computeIfAbsent(e.getKey(), id -> new Track());
            e.getValue().sort(Comparator.comparingLong(Sample::t));
            e.getValue().forEach(s -> record(track, s));
            primeAlerts(track, now);
        }
        log.info("fleet ops: restored samples for {} bots", byBot.size());
    }

    private static void primeAlerts(Track track, long now) {
        long frozen = frozenForMs(track);
        long noExp = noExpForMs(track);
        int loopMap = lastErrandMap(track);
        int starts = loopMap == -1 ? 0 : errandStarts(track, loopMap, now);
        synchronized (track) {
            track.frozenAlerted = frozen >= FROZEN_ALERT_MS;
            track.noExpAlerted = noExp >= NO_EXP_ALERT_MS;
            if (starts >= LOOP_REPEATS) {
                track.loopAlertedAtMs.put(loopMap, now);
            }
        }
    }

    private static Sample sampleOf(BotEntry entry, Character bot, long now) {
        boolean busy = (entry.grinding || BotAutopilotManager.isActive(entry) || entry.autopilotErrandMapId != -1)
                && entry.operatorCmd == null && !entry.loggingOut && !entry.restErrand && now >= entry.breakUntilMs
                && !BotTrainingPlan.complete(entry, bot);
        java.awt.Point p = bot.getPosition();
        return new Sample(now, bot.getMapId(), p.x, p.y, totalExp(bot.getLevel(), bot.getExp()), busy,
                entry.autopilotErrandMapId);
    }

    static void record(Track track, Sample s) {
        synchronized (track) {
            Sample prev = track.samples.peekLast();
            if (s.errandMapId() != -1 && (prev == null || prev.errandMapId() != s.errandMapId())) {
                track.errandStarts.addLast(new long[]{s.t(), s.errandMapId()});
            }
            track.samples.addLast(s);
            while (track.samples.size() > KEEP_SAMPLES) {
                track.samples.removeFirst();
            }
            while (!track.errandStarts.isEmpty() && s.t() - track.errandStarts.peekFirst()[0] > 2 * LOOP_WINDOW_MS) {
                track.errandStarts.removeFirst();
            }
        }
    }

    /** How long the newest sample's bot has stood still, busy and earning nothing; 0 when it isn't. */
    static long frozenForMs(Track track) {
        synchronized (track) {
            Sample last = track.samples.peekLast();
            if (last == null || !last.busy()) {
                return 0L;
            }
            long since = last.t();
            for (var it = track.samples.descendingIterator(); it.hasNext(); ) {
                Sample s = it.next();
                if (!s.busy() || s.mapId() != last.mapId() || s.totalExp() != last.totalExp()
                        || Math.abs(s.x() - last.x()) > STILL_PX || Math.abs(s.y() - last.y()) > STILL_PX) {
                    break;
                }
                since = s.t();
            }
            return last.t() - since;
        }
    }

    /** How long the newest sample's bot has been busy without gaining EXP, wherever it went; 0 when it
     *  isn't busy. Catches a bot that keeps moving but never fights (SipsBuddy1 toured four shops for an
     *  hour with a full bag), which the frozen and loop checks both miss. A death's loss is no gain. */
    static long noExpForMs(Track track) {
        synchronized (track) {
            Sample last = track.samples.peekLast();
            if (last == null || !last.busy()) {
                return 0L;
            }
            long since = last.t();
            for (var it = track.samples.descendingIterator(); it.hasNext(); ) {
                Sample s = it.next();
                if (!s.busy() || s.totalExp() < last.totalExp()) {
                    break;
                }
                since = s.t();
            }
            return last.t() - since;
        }
    }

    /** Errand starts to {@code mapId} within the loop window before {@code nowMs}. */
    static int errandStarts(Track track, int mapId, long nowMs) {
        synchronized (track) {
            int n = 0;
            for (long[] start : track.errandStarts) {
                if (start[1] == mapId && nowMs - start[0] <= LOOP_WINDOW_MS) {
                    n++;
                }
            }
            return n;
        }
    }

    /** The map the newest errand start went to, or -1. */
    static int lastErrandMap(Track track) {
        synchronized (track) {
            long[] last = track.errandStarts.peekLast();
            return last == null ? -1 : (int) last[1];
        }
    }

    /** EXP per hour over the last hour of samples; null with under ten minutes of history. A death's loss
     *  counts as no gain. */
    static Double expPerHour(Track track, long nowMs) {
        synchronized (track) {
            Sample last = track.samples.peekLast();
            Sample first = null;
            for (Sample s : track.samples) {
                if (nowMs - s.t() <= RATE_WINDOW_MS) {
                    first = s;
                    break;
                }
            }
            if (last == null || first == null || last.t() - first.t() < MIN_RATE_SPAN_MS) {
                return null;
            }
            long gain = 0;
            Sample prev = null;
            for (Sample s : track.samples) {
                if (s.t() < first.t()) {
                    continue;
                }
                if (prev != null) {
                    gain += Math.max(0L, s.totalExp() - prev.totalExp());
                }
                prev = s;
            }
            return gain * 3_600_000.0 / (last.t() - first.t());
        }
    }

    /** Alert lines for this sample, once per incident: frozen past the alert time, or a third errand start
     *  to the same map within the loop window. */
    static List<String> alerts(Track track, long nowMs, String botName, String shortName) {
        List<String> out = new ArrayList<>();
        long frozen = frozenForMs(track);
        synchronized (track) {
            if (frozen >= FROZEN_ALERT_MS && !track.frozenAlerted) {
                Sample last = track.samples.peekLast();
                track.frozenAlerted = true;
                out.add(clip(botName + " frozen " + frozen / 60_000 + " min at " + mapName(last.mapId())
                        + " (x " + last.x() + ", y " + last.y() + "). navi rescue " + shortName));
            } else if (frozen == 0) {
                track.frozenAlerted = false;
            }
        }
        long noExp = noExpForMs(track);
        synchronized (track) {
            if (noExp >= NO_EXP_ALERT_MS && !track.noExpAlerted) {
                track.noExpAlerted = true;
                // A frozen bot earns nothing either, and its owner already heard about it.
                if (!track.frozenAlerted) {
                    Sample last = track.samples.peekLast();
                    out.add(clip(botName + " busy " + noExp / 60_000 + " min with no EXP, now at "
                            + mapName(last.mapId()) + ". navi why " + shortName));
                }
            } else if (noExp == 0) {
                track.noExpAlerted = false;
            }
        }
        int loopMap = lastErrandMap(track);
        if (loopMap != -1) {
            int starts = errandStarts(track, loopMap, nowMs);
            synchronized (track) {
                Long alertedAt = track.loopAlertedAtMs.get(loopMap);
                if (starts >= LOOP_REPEATS && (alertedAt == null || nowMs - alertedAt > LOOP_ALERT_COOLDOWN_MS)) {
                    track.loopAlertedAtMs.put(loopMap, nowMs);
                    out.add(clip(botName + " started " + starts + " errands to " + mapName(loopMap) + " in "
                            + LOOP_WINDOW_MS / 60_000 + " min. navi why " + shortName));
                }
            }
        }
        return out;
    }

    private static void notifyOwner(BotEntry entry, Character bot, List<String> lines) {
        Integer ownerId = BotOwnershipService.getInstance().getRegisteredOwnerId(bot.getId());
        if (ownerId == null) {
            return;
        }
        Character owner = Server.getInstance().getWorld(bot.getWorld()).getPlayerStorage().getCharacterById(ownerId);
        if (owner == null || !owner.isLoggedinWorld()) {
            return; // navi fleet still lists it when the owner is back
        }
        log.info("fleet ops: alerting {} about {}: {}", owner.getName(), bot.getName(), String.join(" / ", lines));
        BotNaviManager.whisperLater(bot, owner, lines, BotManager.randMs(300, 700));
    }

    // ---- commands ----------------------------------------------------------------------------------

    /** Lines to whisper for a fleet command, or null when {@code request} isn't one. */
    static List<String> handle(Character owner, String request) {
        String req = request == null ? "" : request.strip();
        if (FLEET.matcher(req).matches()) {
            return fleetForOwner(owner.getId());
        }
        Matcher m = ACTION.matcher(req);
        if (!m.matches()) {
            return null;
        }
        List<BotEntry> mine = owned(owner.getId());
        List<String> names = mine.stream().map(e -> e.bot.getName()).toList();
        List<String> found = resolve(names, m.group(2));
        if (found.isEmpty()) {
            return List.of("no bot of yours called " + m.group(2));
        }
        if (found.size() > 1) {
            return List.of(clip(m.group(2) + " could be " + String.join(", ", found)));
        }
        BotEntry entry = mine.get(names.indexOf(found.get(0)));
        return switch (m.group(1).toLowerCase(Locale.ROOT)) {
            case "why" -> why(entry, System.currentTimeMillis());
            case "park" -> park(entry);
            default -> rescue(entry);
        };
    }

    /** True for the bare navi and navi help, which answer with the command list. */
    static boolean isHelp(String request) {
        return HELP.matcher(request == null ? "" : request.strip()).matches();
    }

    /** The navi fleet lines for one owner; also served at /api/fleetops for checking without the game. */
    static List<String> fleetForOwner(int ownerId) {
        return fleet(owned(ownerId), System.currentTimeMillis());
    }

    private static List<BotEntry> owned(int ownerId) {
        List<BotEntry> out = new ArrayList<>();
        for (BotEntry e : BotManager.getInstance().allEntries()) {
            if (e.bot != null && Objects.equals(BotOwnershipService.getInstance().getRegisteredOwnerId(e.bot.getId()), ownerId)) {
                out.add(e);
            }
        }
        out.sort(Comparator.comparing(e -> e.bot.getName()));
        return out;
    }

    /** Names matching {@code arg}: the exact name, else names ending in it when it is a number that the
     *  name doesn't merely end with more digits of (26 matches SipsBuddy26, not SipsBuddy126). */
    static List<String> resolve(List<String> names, String arg) {
        for (String n : names) {
            if (n.equalsIgnoreCase(arg)) {
                return List.of(n);
            }
        }
        List<String> out = new ArrayList<>();
        if (arg.matches("\\d+")) {
            for (String n : names) {
                int cut = n.length() - arg.length();
                if (cut > 0 && n.endsWith(arg) && !java.lang.Character.isDigit(n.charAt(cut - 1))) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    private static List<String> fleet(List<BotEntry> mine, long now) {
        if (mine.isEmpty()) {
            return List.of("no bots of yours are online");
        }
        int training = 0;
        double fleetRate = 0;
        boolean warming = false;
        List<String> stuck = new ArrayList<>();
        List<String> looping = new ArrayList<>();
        List<String> earningNothing = new ArrayList<>();
        List<String> capped = new ArrayList<>();
        List<Map.Entry<String, Double>> rates = new ArrayList<>();
        for (BotEntry e : mine) {
            Character bot = e.bot;
            if (BotTrainingPlan.complete(e, bot)) {
                capped.add(shortName(bot.getName()));
                continue;
            }
            training++;
            Track t = tracks.get(bot.getId());
            if (t == null) {
                warming = true;
                continue;
            }
            Double rate = expPerHour(t, now);
            if (rate == null) {
                warming = true;
            } else {
                fleetRate += rate;
                rates.add(Map.entry(bot.getName(), rate));
            }
            long frozen = frozenForMs(t);
            if (frozen >= FROZEN_LIST_MS) {
                stuck.add(bot.getName() + " " + mapName(bot.getMapId()) + " " + frozen / 60_000 + " min");
            } else {
                long noExp = noExpForMs(t);
                if (noExp >= NO_EXP_LIST_MS) {
                    earningNothing.add(bot.getName() + " " + noExp / 60_000 + " min");
                }
            }
            int loopMap = lastErrandMap(t);
            int starts = loopMap == -1 ? 0 : errandStarts(t, loopMap, now);
            if (starts >= LOOP_REPEATS) {
                looping.add(bot.getName() + " " + mapName(loopMap) + " x" + starts);
            }
        }
        List<String> out = new ArrayList<>();
        out.add(training + " training, " + (warming && rates.isEmpty() ? "EXP/h after 10 min of samples"
                : fmt(fleetRate) + " EXP/h last hour") + (capped.isEmpty() ? "" : ", " + capped.size() + " capped"));
        out.add(clip("Stuck: " + (stuck.isEmpty() ? "none" : String.join("; ", stuck))));
        out.add(clip("Looping: " + (looping.isEmpty() ? "none" : String.join("; ", looping))));
        out.add(clip("No EXP: " + (earningNothing.isEmpty() ? "none" : String.join("; ", earningNothing))));
        if (!rates.isEmpty()) {
            rates.sort(Map.Entry.comparingByValue());
            List<String> slow = rates.stream().limit(2).map(r -> r.getKey() + " " + fmt(r.getValue())).toList();
            out.add(clip("Slowest: " + String.join(", ", slow)));
        }
        if (!capped.isEmpty()) {
            out.add(clip("Capped: " + String.join(", ", capped)));
        }
        return out;
    }

    private static List<String> why(BotEntry entry, long now) {
        Character bot = entry.bot;
        List<String> out = new ArrayList<>();
        out.add(clip(bot.getName() + " L" + bot.getLevel() + " at " + mapName(bot.getMapId())));
        out.add(clip(BotAutopilotManager.statusReport(entry, bot)));
        Track t = tracks.get(bot.getId());
        if (t != null) {
            Double rate = expPerHour(t, now);
            long frozen = frozenForMs(t);
            long noExp = noExpForMs(t);
            int loopMap = lastErrandMap(t);
            int starts = loopMap == -1 ? 0 : errandStarts(t, loopMap, now);
            out.add(clip((rate == null ? "EXP/h not known yet" : fmt(rate) + " EXP/h last hour")
                    + (frozen >= FROZEN_LIST_MS ? ", still for " + frozen / 60_000 + " min"
                    : noExp >= NO_EXP_LIST_MS ? ", no EXP for " + noExp / 60_000 + " min" : "")
                    + (starts >= 2 ? ", " + starts + " errands to " + mapName(loopMap) + " in 45 min" : "")));
        }
        return out;
    }

    private static List<String> rescue(BotEntry entry) {
        Character bot = entry.bot;
        String from = mapName(bot.getMapId());
        if (BotManager.getInstance().tryUseReturnScroll(bot)) {
            BotManager.getInstance().resumeFromOperatorCommand(entry); // back to its own plan, from town
            Track t = tracks.get(bot.getId());
            if (t != null) {
                synchronized (t) {
                    t.samples.clear(); // a fresh start: don't flag it stuck from before the scroll
                }
            }
            log.info("fleet ops: rescued {} from {} by return scroll", bot.getName(), from);
            return List.of(clip(bot.getName() + " scrolled from " + from + " to " + mapName(bot.getMapId())));
        }
        int town = bot.getMap().getReturnMapId();
        if (town > 0 && town != 999999999 && town != bot.getMapId()) {
            BotManager.getInstance().applyOperatorCommand(entry, BotEntry.OperatorCmd.MOVE, town, 0);
            return List.of(clip(bot.getName() + " has no return scroll it can use; walking it to " + mapName(town)));
        }
        return List.of(clip(bot.getName() + " has no return scroll and is already in town"));
    }

    private static List<String> park(BotEntry entry) {
        Character bot = entry.bot;
        if (!BotTrainingPlan.canPark(entry)) {
            return List.of(clip(bot.getName() + " isn't an active roster bot, so it can't be parked"));
        }
        if (!BotTrainingPlan.park(entry, bot, "parked by owner via navi")) {
            return List.of("couldn't save the park, check the server log");
        }
        return List.of(clip(bot.getName() + " parked and logging out; set rosterActive true to bring it back"));
    }

    // ---- formatting --------------------------------------------------------------------------------

    static long totalExp(int level, int exp) {
        int l = Math.max(1, Math.min(level, CUMULATIVE_EXP.length - 1));
        return CUMULATIVE_EXP[l] + Math.max(0, exp);
    }

    private static long[] cumulativeExp() {
        long[] sum = new long[201];
        for (int l = 2; l <= 200; l++) {
            sum[l] = sum[l - 1] + ExpTable.getExpNeededForLevel(l - 1);
        }
        return sum;
    }

    static String fmt(double v) {
        if (v >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", v / 1_000_000);
        }
        if (v >= 1_000) {
            return Math.round(v / 1_000) + "k";
        }
        return String.valueOf(Math.round(v));
    }

    /** The number at the end of a name (26 for SipsBuddy26), which navi commands accept; else the name. */
    static String shortName(String name) {
        Matcher m = Pattern.compile("(\\d+)$").matcher(name);
        return m.find() && m.start() > 0 ? m.group(1) : name;
    }

    /** Seam: names come from String.wz, which unit tests don't load. */
    static java.util.function.IntFunction<String> mapNameLookup = BotFleetOps::loadMapName;

    private static String mapName(int mapId) {
        return mapNameLookup.apply(mapId);
    }

    private static String loadMapName(int mapId) {
        try {
            String name = MapFactory.loadPlaceName(mapId);
            return name != null && !name.isBlank() ? name : "map " + mapId;
        } catch (RuntimeException e) {
            return "map " + mapId;
        }
    }

    static String clip(String s) {
        return s.length() <= MAX_LINE ? s : s.substring(0, MAX_LINE - 3).stripTrailing() + "...";
    }
}
