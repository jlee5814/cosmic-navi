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
    static final long AUTO_RESCUE_FROZEN_MS = 20 * 60_000L;
    static final long AUTO_RESCUE_NO_EXP_MS = 45 * 60_000L;
    static final int AUTO_RESCUE_MAX = 2;
    static final long AUTO_RESCUE_WINDOW_MS = 2 * 60 * 60_000L;
    // Low EXP: earning something, but under a fifth of its usual pace for half an hour. The usual pace is
    // the bot's best recent 30 minute rate, a peak that halves every 6 hours, never below half its level
    // band's median, so a bot that was slow since it logged in still has a bar.
    static final long PACE_WINDOW_MS = 30 * 60_000L;
    static final double LOW_EXP_FRACTION = 0.2;
    static final long LOW_EXP_LIST_MS = 15 * 60_000L;
    static final long LOW_EXP_ACT_MS = 30 * 60_000L;
    static final long PACE_HALF_LIFE_MS = 6 * 60 * 60_000L;
    static final int PACE_BAND_LEVELS = 5;
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
        long resetAtMs;                                         // a rescue starts the stall clocks over
        final ArrayDeque<Long> autoRescuesAtMs = new ArrayDeque<>();
        boolean autoRescueGaveUpAlerted;
        final ArrayDeque<Long> responderActsAtMs = new ArrayDeque<>();
        int level;
        double paceExpPerHour;      // usual pace: best recent 30 min rate, halving every 6 h
        long paceAtMs;
        double expectedExpPerHour;  // the bar a low rate is measured against
        Double recentExpPerHour;    // the last 30 minutes, or null without a full busy window
        long lowSinceMs;            // 0 when not low
        boolean lowAlerted;
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
                updatePace(track, now, bot.getLevel(), bandMedian(bot.getLevel(), bot.getId(), tracks));
                List<String> alerts = new ArrayList<>(alerts(track, now, bot.getName(), shortName(bot.getName())));
                AutoRescue ar = autoRescue(track, now);
                if (ar != null && ar.rescue()) {
                    entry.nextTickTask = () -> runAutoRescue(entry, ar.why());
                } else if (ar != null) {
                    alerts.add(clip(bot.getName() + " " + ar.why() + " at " + mapName(bot.getMapId()) + " after "
                            + AUTO_RESCUE_MAX + " auto rescues in " + AUTO_RESCUE_WINDOW_MS / 3_600_000
                            + " h; it needs a fix. navi why " + shortName(bot.getName())));
                }
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
                // P lines: the usual pace outlives the 90 minute window, so a slow spell after a restart
                // is still measured against the bot's real pace.
                Track t = e.getValue();
                if (t.paceAtMs > 0) {
                    sb.append("P\t").append(e.getKey()).append('\t').append(Math.round(t.paceExpPerHour)).append('\t')
                            .append(t.paceAtMs).append('\t').append(t.level).append('\n');
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
                if (f.length == 5 && "P".equals(f[0])) {
                    Track t = tracks.computeIfAbsent(Integer.parseInt(f[1]), id -> new Track());
                    synchronized (t) {
                        t.paceExpPerHour = Double.parseDouble(f[2]);
                        t.paceAtMs = Long.parseLong(f[3]);
                        t.level = Integer.parseInt(f[4]);
                    }
                    continue;
                }
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
        int level;
        synchronized (track) {
            level = track.level;
        }
        updatePace(track, now, level, 0);
        long low = lowExpForMs(track, now);
        synchronized (track) {
            track.lowAlerted = low >= LOW_EXP_ACT_MS;
        }
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
                && !BotTrainingPlan.complete(entry, bot)
                && !BotFerryManager.isWaitingOrRiding(entry, bot); // standing on a dock or a deck is the plan
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
                if (s.t() < track.resetAtMs || !s.busy() || s.mapId() != last.mapId() || s.totalExp() != last.totalExp()
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
                if (s.t() < track.resetAtMs || !s.busy() || s.totalExp() < last.totalExp()) {
                    break;
                }
                since = s.t();
            }
            return last.t() - since;
        }
    }

    /** EXP per hour over the last {@link #PACE_WINDOW_MS} of one unbroken busy stretch since the last rescue;
     *  null until that stretch spans nearly the whole window. */
    static Double recentRate(Track t, long now) {
        synchronized (t) {
            Sample last = t.samples.peekLast();
            if (last == null || !last.busy()) {
                return null;
            }
            Sample first = null;
            Sample prev = null;
            long gain = 0;
            for (Sample s : t.samples) {
                if (s.t() < t.resetAtMs || now - s.t() > PACE_WINDOW_MS) {
                    continue;
                }
                if (!s.busy()) { // a break or a ferry ride splits the stretch
                    first = null;
                    prev = null;
                    gain = 0;
                    continue;
                }
                if (first == null) {
                    first = s;
                }
                if (prev != null) {
                    gain += Math.max(0L, s.totalExp() - prev.totalExp());
                }
                prev = s;
            }
            if (first == null || last.t() - first.t() < PACE_WINDOW_MS - 2 * SAMPLE_MS) {
                return null;
            }
            return gain * 3_600_000.0 / (last.t() - first.t());
        }
    }

    /** Median usual pace of the other bots within {@link #PACE_BAND_LEVELS} levels, or 0 with fewer than two. */
    static double bandMedian(int level, int selfId, Map<Integer, Track> all) {
        List<Double> paces = new ArrayList<>();
        for (var e : all.entrySet()) {
            if (e.getKey() == selfId) {
                continue;
            }
            Track o = e.getValue();
            synchronized (o) {
                if (o.level > 0 && Math.abs(o.level - level) <= PACE_BAND_LEVELS && o.paceExpPerHour > 0) {
                    paces.add(o.paceExpPerHour);
                }
            }
        }
        if (paces.size() < 2) {
            return 0;
        }
        paces.sort(null);
        return paces.get(paces.size() / 2);
    }

    /** Once a minute: refresh the usual pace and the low EXP clock. Zero EXP is the no EXP check's job, so
     *  low means earning something, but under {@link #LOW_EXP_FRACTION} of the bar. */
    static void updatePace(Track t, long now, int level, double bandMedian) {
        Double r = recentRate(t, now);
        synchronized (t) {
            t.level = level;
            double decayed = t.paceAtMs == 0 ? 0
                    : t.paceExpPerHour * Math.pow(0.5, Math.max(0, now - t.paceAtMs) / (double) PACE_HALF_LIFE_MS);
            t.paceExpPerHour = r == null ? decayed : Math.max(decayed, r);
            t.paceAtMs = now;
            t.recentExpPerHour = r;
            t.expectedExpPerHour = Math.max(t.paceExpPerHour, bandMedian / 2);
            boolean low = r != null && r > 0 && r < LOW_EXP_FRACTION * t.expectedExpPerHour;
            if (!low) {
                t.lowSinceMs = 0;
            } else if (t.lowSinceMs == 0) {
                t.lowSinceMs = now - PACE_WINDOW_MS; // the whole window just measured was already low
            }
        }
    }

    /** How long the bot has earned under a fifth of its usual pace; 0 when it isn't. */
    static long lowExpForMs(Track t, long now) {
        synchronized (t) {
            return t.lowSinceMs == 0 ? 0 : now - t.lowSinceMs;
        }
    }

    static final double ONE_MAP_SHARE = 0.8;

    /** True when at least {@link #ONE_MAP_SHARE} of the samples in the last {@code windowMs} are on the newest
     *  sample's map: the bot is stuck on a map, not crossing several on a long walk, where a scroll would only
     *  send it back to town. Not all of them: SipsBuddy3 wandered two maps over for four minutes and came back
     *  to the same spot on 682000100. */
    static boolean onOneMapFor(Track t, long now, long windowMs) {
        synchronized (t) {
            Sample last = t.samples.peekLast();
            if (last == null) {
                return false;
            }
            int in = 0;
            int on = 0;
            for (Sample s : t.samples) {
                if (now - s.t() > windowMs) {
                    continue;
                }
                in++;
                if (s.mapId() == last.mapId()) {
                    on++;
                }
            }
            return in > 0 && on >= ONE_MAP_SHARE * in;
        }
    }

    /** "11k EXP/h, usual 220k" for whispers and navi fleet. */
    private static String lowDetail(Track t) {
        synchronized (t) {
            return fmt(t.recentExpPerHour == null ? 0 : t.recentExpPerHour) + " EXP/h, usual " + fmt(t.expectedExpPerHour);
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
        long low = lowExpForMs(track, nowMs);
        String lowDetail = lowDetail(track);
        synchronized (track) {
            if (low >= LOW_EXP_ACT_MS && !track.lowAlerted) {
                track.lowAlerted = true;
                Sample last = track.samples.peekLast();
                out.add(clip(botName + " earning " + lowDetail + ", for " + low / 60_000 + " min at "
                        + mapName(last.mapId()) + ". navi why " + shortName));
            } else if (low == 0) {
                track.lowAlerted = false;
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
        List<String> earningLittle = new ArrayList<>();
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
                long low = lowExpForMs(t, now);
                if (noExp >= NO_EXP_LIST_MS) {
                    earningNothing.add(bot.getName() + " " + noExp / 60_000 + " min");
                } else if (low >= LOW_EXP_LIST_MS) {
                    earningLittle.add(bot.getName() + " " + lowDetail(t) + ", " + low / 60_000 + " min");
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
        out.add(clip("Low EXP: " + (earningLittle.isEmpty() ? "none" : String.join("; ", earningLittle))));
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
                    : noExp >= NO_EXP_LIST_MS ? ", no EXP for " + noExp / 60_000 + " min"
                    : lowExpForMs(t, now) >= LOW_EXP_LIST_MS ? ", earning " + lowDetail(t) : "")
                    + (starts >= 2 ? ", " + starts + " errands to " + mapName(loopMap) + " in 45 min" : "")));
        }
        return out;
    }

    /** Town return scroll, then back to the bot's own plan from town; the stall clocks start over. False
     *  when it has no scroll it can use here. */
    private static boolean scrollOut(BotEntry entry, long now) {
        Character bot = entry.bot;
        if (!BotManager.getInstance().tryUseReturnScroll(bot)) {
            return false;
        }
        BotManager.getInstance().resumeFromOperatorCommand(entry);
        Track t = tracks.get(bot.getId());
        if (t != null) {
            synchronized (t) {
                t.resetAtMs = now; // keep the EXP history; only the stall clocks restart
            }
        }
        return true;
    }

    // ---- auto rescue -------------------------------------------------------------------------------

    /** What the sampler should do about a stalled bot: scroll it out, or tell the owner it gave up. */
    record AutoRescue(boolean rescue, String why) {}

    /** Decide, once a minute, whether to scroll a bot out without waiting for {@code navi rescue}: frozen for
     *  {@link #AUTO_RESCUE_FROZEN_MS}, or busy on its own map plan (not an errand) with no EXP for
     *  {@link #AUTO_RESCUE_NO_EXP_MS}, or earning under a fifth of its usual pace for {@link #LOW_EXP_ACT_MS} while
     *  on one map (SipsBuddy3 hovered on Haunted House 682000100 at 11k EXP/h against its usual 220k). An errand
     *  loop is left alone, since a scroll only restarts it. At most {@link #AUTO_RESCUE_MAX} per
     *  {@link #AUTO_RESCUE_WINDOW_MS}; past that it says so once and stops, since a spot that refreezes the bot
     *  needs a code fix, not more scrolls. */
    static AutoRescue autoRescue(Track track, long now) {
        long frozen = frozenForMs(track);
        long noExp = noExpForMs(track);
        long low = lowExpForMs(track, now);
        boolean oneMap = onOneMapFor(track, now, PACE_WINDOW_MS);
        String lowDetail = lowDetail(track);
        synchronized (track) {
            Sample last = track.samples.peekLast();
            boolean ownPlan = last != null && last.errandMapId() == -1;
            String why = frozen >= AUTO_RESCUE_FROZEN_MS ? "frozen " + frozen / 60_000 + " min"
                    : noExp >= AUTO_RESCUE_NO_EXP_MS && ownPlan ? "busy " + noExp / 60_000 + " min with no EXP"
                    : low >= LOW_EXP_ACT_MS && ownPlan && oneMap
                    ? "earning " + lowDetail + ", for " + low / 60_000 + " min" : null;
            if (why == null) {
                track.autoRescueGaveUpAlerted = false;
                return null;
            }
            while (!track.autoRescuesAtMs.isEmpty() && now - track.autoRescuesAtMs.peekFirst() > AUTO_RESCUE_WINDOW_MS) {
                track.autoRescuesAtMs.removeFirst();
            }
            if (track.autoRescuesAtMs.size() >= AUTO_RESCUE_MAX) {
                if (track.autoRescueGaveUpAlerted) {
                    return null;
                }
                track.autoRescueGaveUpAlerted = true;
                return new AutoRescue(false, why);
            }
            track.autoRescuesAtMs.addLast(now);
            track.resetAtMs = now; // no second decision while the scroll waits for the bot's tick
            return new AutoRescue(true, why);
        }
    }

    /** Runs on the bot's own tick (via {@link BotEntry#nextTickTask}), so the scroll can't race its movement. */
    static void runAutoRescue(BotEntry entry, String why) {
        Character bot = entry.bot;
        if (bot == null || bot.getMap() == null || entry.operatorCmd != null || entry.loggingOut) {
            return; // the owner or a logout took over since the decision
        }
        String from = mapName(bot.getMapId());
        String name = bot.getName();
        if (scrollOut(entry, System.currentTimeMillis())) {
            log.info("fleet ops: auto rescued {} ({}) from {} by return scroll", name, why, from);
            notifyOwner(entry, bot, List.of(clip(name + " was " + why + " at " + from + "; navi scrolled it to "
                    + mapName(bot.getMapId()))));
        } else {
            log.info("fleet ops: couldn't auto rescue {} ({}) at {}: no usable return scroll", name, why, from);
            notifyOwner(entry, bot, List.of(clip(name + " is " + why + " at " + from
                    + " with no usable return scroll. navi rescue " + shortName(name))));
        }
    }

    // ---- responder hooks (tools/responder asks a model what to do about stalls rules can't settle) -------

    static final int RESPONDER_MAX_ACTS = 3;
    static final long RESPONDER_WINDOW_MS = 2 * 60 * 60_000L;
    static final int HOLD_ERRANDS_MAX_MIN = 60;
    static final java.util.Set<String> RESPONDER_ACTIONS = java.util.Set.of("rescue", "hold_errands", "sell_trash", "park");

    /** Why this bot is an open incident for the responder, or null: a scroll can't fix an errand loop or a
     *  bot earning nothing on an errand, and a bot past its auto rescues needs another idea. */
    static String incidentKind(Track t, long now) {
        long noExp = noExpForMs(t);
        long low = lowExpForMs(t, now);
        int loopMap = lastErrandMap(t);
        int starts = loopMap == -1 ? 0 : errandStarts(t, loopMap, now);
        synchronized (t) {
            Sample last = t.samples.peekLast();
            if (last == null || !last.busy()) {
                return null;
            }
            if (t.autoRescueGaveUpAlerted) {
                return "refreezing";
            }
            if (starts >= LOOP_REPEATS && last.errandMapId() == loopMap) {
                return "errand_loop";
            }
            if (noExp >= NO_EXP_ALERT_MS && last.errandMapId() != -1) {
                return "no_exp_on_errand";
            }
            if (noExp >= NO_EXP_ALERT_MS && !t.autoRescuesAtMs.isEmpty()) {
                return "no_exp_after_rescue";
            }
            if (low >= LOW_EXP_ACT_MS && last.errandMapId() != -1) {
                return "low_exp_on_errand";
            }
            if (low >= LOW_EXP_ACT_MS && !t.autoRescuesAtMs.isEmpty()) {
                return "low_exp_after_rescue";
            }
            return null;
        }
    }

    /** Every open incident as a JSON array: the facts a model needs to pick an action. */
    static String incidentsJson(long now) {
        List<String> out = new ArrayList<>();
        for (BotEntry e : BotManager.getInstance().allEntries()) {
            Character bot = e.bot;
            Track t = bot == null ? null : tracks.get(bot.getId());
            if (t == null || bot.getMap() == null) {
                continue;
            }
            String kind = incidentKind(t, now);
            if (kind == null) {
                continue;
            }
            try {
                out.add(incidentJson(e, bot, t, kind, now));
            } catch (RuntimeException ex) {
                log.debug("fleet ops: incident for {} failed", bot.getName(), ex);
            }
        }
        return "[" + String.join(",", out) + "]";
    }

    private static String incidentJson(BotEntry e, Character bot, Track t, String kind, long now) {
        int loopMap = lastErrandMap(t);
        int errand = e.autopilotErrandMapId;
        int rescues;
        int acts;
        synchronized (t) {
            rescues = t.autoRescuesAtMs.size();
            acts = t.responderActsAtMs.size();
        }
        StringBuilder b = new StringBuilder("{");
        b.append("\"id\":").append(bot.getId())
                .append(",\"name\":").append(js(bot.getName()))
                .append(",\"kind\":").append(js(kind))
                .append(",\"level\":").append(bot.getLevel())
                .append(",\"job\":").append(bot.getJob().getId())
                .append(",\"map\":").append(bot.getMapId())
                .append(",\"mapName\":").append(js(mapName(bot.getMapId())))
                .append(",\"x\":").append(bot.getPosition().x).append(",\"y\":").append(bot.getPosition().y)
                .append(",\"errandMap\":").append(errand)
                .append(",\"errandMapName\":").append(js(errand == -1 ? "" : mapName(errand)))
                .append(",\"noExpMin\":").append(noExpForMs(t) / 60_000)
                .append(",\"frozenMin\":").append(frozenForMs(t) / 60_000)
                .append(",\"lowExpMin\":").append(lowExpForMs(t, now) / 60_000)
                .append(",\"expPerHourLast30Min\":").append(Math.round(t.recentExpPerHour == null ? 0 : t.recentExpPerHour))
                .append(",\"usualExpPerHour\":").append(Math.round(t.expectedExpPerHour))
                .append(",\"errandStartsLast45Min\":").append(loopMap == -1 ? 0 : errandStarts(t, loopMap, now))
                .append(",\"lastErrandMapName\":").append(js(loopMap == -1 ? "" : mapName(loopMap)))
                .append(",\"autoRescuesLast2h\":").append(rescues)
                .append(",\"responderActsLast2h\":").append(acts)
                .append(",\"meso\":").append(bot.getMeso())
                .append(",\"status\":").append(js(BotAutopilotManager.statusReport(e, bot)))
                .append(",\"bag\":{");
        String[] tabs = {"EQUIP", "USE", "ETC"};
        client.inventory.InventoryType[] types = {client.inventory.InventoryType.EQUIP,
                client.inventory.InventoryType.USE, client.inventory.InventoryType.ETC};
        for (int i = 0; i < tabs.length; i++) {
            var inv = bot.getInventory(types[i]);
            int slots = inv == null ? 0 : inv.getSlotLimit();
            int free = inv == null ? 0 : inv.getNumFreeSlot();
            b.append(i == 0 ? "" : ",").append('"').append(tabs[i]).append("\":{\"used\":").append(slots - free)
                    .append(",\"slots\":").append(slots).append('}');
        }
        b.append("},\"use\":[");
        // USE stacks grouped by item, most slots first, with how the sell rules classify them.
        Map<Integer, int[]> slotsQty = new HashMap<>();
        Map<Integer, String> why = new HashMap<>();
        for (var c : BotInventoryManager.classifyBagUse(bot).entrySet()) {
            int id = c.getKey().getItemId();
            int[] sq = slotsQty.computeIfAbsent(id, k -> new int[2]);
            sq[0]++;
            sq[1] += c.getKey().getQuantity();
            why.merge(id, c.getValue().tier() + " " + c.getValue().reason(), (a, x) -> a.contains(x) ? a : a + "; " + x);
        }
        List<Integer> ids = new ArrayList<>(slotsQty.keySet());
        ids.sort(Comparator.comparingInt((Integer id) -> -slotsQty.get(id)[0]));
        for (int i = 0; i < Math.min(15, ids.size()); i++) {
            int id = ids.get(i);
            String name = server.ItemInformationProvider.getInstance().getName(id);
            b.append(i == 0 ? "" : ",").append("{\"id\":").append(id)
                    .append(",\"name\":").append(js(name == null ? "item " + id : name))
                    .append(",\"slots\":").append(slotsQty.get(id)[0])
                    .append(",\"qty\":").append(slotsQty.get(id)[1])
                    .append(",\"rule\":").append(js(why.get(id))).append('}');
        }
        return b.append("]}").toString();
    }

    private static String js(String s) {
        return BotWorldGraphWebServer.jsonStr(s == null ? "" : s);
    }

    /** Queue one responder action on the bot's next tick. Each bot takes at most {@link #RESPONDER_MAX_ACTS} per
     *  {@link #RESPONDER_WINDOW_MS}, so a model that keeps picking wrong can't churn it. Returns "queued ..." or
     *  why not. hold_errands: drop the current errand and allow none for {@code arg} minutes (at most
     *  {@link #HOLD_ERRANDS_MAX_MIN}), so the bot grinds instead of riding a loop; sell_trash: a sell visit at a
     *  shop on this map; park: the cap check's park, for roster bots only; rescue: the navi rescue scroll. */
    static String act(int botId, String action, int arg, String reason, long now) {
        BotEntry entry = BotManager.getInstance().getEntryByBotCharId(botId);
        if (entry == null || entry.bot == null) {
            return "no bot " + botId + " online";
        }
        if (!RESPONDER_ACTIONS.contains(action)) {
            return "unknown action " + action + "; use one of " + RESPONDER_ACTIONS;
        }
        if ("park".equals(action) && !BotTrainingPlan.canPark(entry)) {
            return entry.bot.getName() + " isn't an active roster bot, so it can't be parked";
        }
        Track t = tracks.computeIfAbsent(botId, id -> new Track());
        synchronized (t) {
            while (!t.responderActsAtMs.isEmpty() && now - t.responderActsAtMs.peekFirst() > RESPONDER_WINDOW_MS) {
                t.responderActsAtMs.removeFirst();
            }
            if (t.responderActsAtMs.size() >= RESPONDER_MAX_ACTS) {
                return entry.bot.getName() + " already had " + RESPONDER_MAX_ACTS + " responder actions in 2 h";
            }
            t.responderActsAtMs.addLast(now);
        }
        int minutes = Math.max(15, Math.min(HOLD_ERRANDS_MAX_MIN, arg));
        String why = reason.length() > 120 ? reason.substring(0, 120) : reason;
        entry.nextTickTask = () -> runResponderAction(entry, action, minutes, why);
        log.info("fleet ops: responder queued {} for {}: {}", action, entry.bot.getName(), why);
        return "queued " + action + " for " + entry.bot.getName();
    }

    private static void runResponderAction(BotEntry entry, String action, int minutes, String why) {
        Character bot = entry.bot;
        if (bot == null || bot.getMap() == null || entry.loggingOut) {
            return;
        }
        long now = System.currentTimeMillis();
        String name = bot.getName();
        String from = mapName(bot.getMapId());
        String done = switch (action) {
            case "rescue" -> scrollOut(entry, now) ? "scrolled from " + from + " to " + mapName(bot.getMapId())
                    : "had no usable return scroll at " + from;
            case "hold_errands" -> {
                holdErrands(entry, now, minutes);
                yield "holds errands for " + minutes + " min and goes back to grinding";
            }
            case "sell_trash" -> {
                BotShopManager.requestSellTrashVisit(entry, bot);
                yield entry.shopVisitPending ? "is selling trash at " + from : "found no shop or nothing to sell at " + from;
            }
            case "park" -> BotTrainingPlan.park(entry, bot, "parked by the navi responder: " + why)
                    ? "parked and logging out" : "couldn't be parked";
            default -> "did nothing";
        };
        log.info("fleet ops: responder {} {}: {} ({})", action, name, done, why);
        notifyOwner(entry, bot, List.of(clip("responder: " + name + " " + done), clip("why: " + why)));
    }

    /** Drop the current errand and start the errand cooldown the autopilot already honors, so every optional
     *  trip (a weapon upgrade, a full bag) waits; the bot heads back to its grind map. */
    static void holdErrands(BotEntry entry, long now, int minutes) {
        if (entry.autopilotErrandMapId != -1) {
            entry.autopilotErrandMapId = -1;
            entry.autopilotReturningFromErrand = true;
        }
        entry.autopilotNextErrandAtMs = Math.max(entry.autopilotNextErrandAtMs, now + minutes * 60_000L);
    }

    private static List<String> rescue(BotEntry entry) {
        Character bot = entry.bot;
        String from = mapName(bot.getMapId());
        if (scrollOut(entry, System.currentTimeMillis())) {
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
