package server.bots;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.bots.llm.NaviRouter;
import tools.PacketCreator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owner Navi tools. The owner whispers one of their bots ("navi email"); the bot asks the local
 * Navi service (tools/navi) for a private result and whispers it back.
 *
 * <p>Privacy rules:
 * <ul>
 *   <li>Only the bot's registered owner can use it, and only by whisper. A navi command in map or
 *       party chat gets a nudge to whisper instead and never reaches the service.</li>
 *   <li>Results go straight to the whispering owner, not through {@code entry.replyChannel}, so a
 *       later map command can't redirect private text into map chat.</li>
 *   <li>Result text is never logged.</li>
 * </ul>
 * Credentials live in the service on the host; the game server only holds {@code NAVI_TOKEN}.
 */
public final class BotNaviManager {
    private static final Logger log = LoggerFactory.getLogger(BotNaviManager.class);

    private static final Pattern NAVI_PATTERN = Pattern.compile("^navi\\b\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^(?:(?:check|read|any)\\s+)?(?:(?:my|new)\\s+)*(?:e-?mails?|inbox|mail)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LLM_PATTERN = Pattern.compile("^llm(?:\\s+stats)?$", Pattern.CASE_INSENSITIVE);
    static final String EMAIL_TOOL = "/v1/email";
    static final int MAX_LINES = 5;

    private static final String ENDPOINT = envOr("NAVI_ENDPOINT", "http://host.docker.internal:8790");
    private static final String TOKEN = envOr("NAVI_TOKEN", "");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private static final List<String> ACKS = List.of("on it", "checking, one sec", "sure, looking now");

    /** Owners with a request in flight; one at a time each so repeated whispers don't stack. */
    private static final Set<Integer> inflight = ConcurrentHashMap.newKeySet();

    private static final Pattern CONFIRM_PATTERN = Pattern.compile("^(?:yes|y|yep|yeah|ok|confirm|do it)$",
            Pattern.CASE_INSENSITIVE);
    static final long CONFIRM_TTL_MS = 60_000;
    static final Map<Integer, Pending> pending = new ConcurrentHashMap<>();

    /** Routing calls get one thread of their own, apart from the chat gate in BotLlmReplyManager, which
     *  drops a call when its permits are busy. Owner requests are rare, so they queue here instead. */
    private static final ExecutorService ROUTER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "navi-router");
        t.setDaemon(true);
        return t;
    });

    private BotNaviManager() {}

    static boolean isNaviCommand(String message) {
        return message != null && NAVI_PATTERN.matcher(message.strip()).matches();
    }

    /** Service path for a navi command, or null when the command isn't one Navi knows. */
    static String toolFor(String message) {
        if (message == null) {
            return null;
        }
        Matcher m = NAVI_PATTERN.matcher(message.strip());
        if (!m.matches()) {
            return null;
        }
        return toolForRequest(m.group(1).replaceAll("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$", ""));
    }

    private static String toolForRequest(String request) {
        return EMAIL_PATTERN.matcher(request).matches() ? EMAIL_TOOL : null;
    }

    /** {@code navi llm}: latency, throughput and prefix cache stats for bot LLM calls. */
    static boolean isLlmRequest(String request) {
        return request != null && LLM_PATTERN.matcher(request).matches();
    }

    /** Lines to whisper for a finished service call. Never echoes exception text. */
    static List<String> resultLines(int status, String body, Throwable error) {
        if (error != null) {
            return List.of("can't reach the navi service, is it running on the host?");
        }
        if (status == 401) {
            return List.of("the navi service rejected this server's token");
        }
        List<String> lines = (body == null ? "" : body).lines()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .limit(MAX_LINES)
                .toList();
        return lines.isEmpty() ? List.of("navi came back empty") : lines;
    }

    /** Map or party chat: nudge once (first bot only) and never call the service. */
    static void declineOutsideWhisper(BotEntry entry) {
        if (BotManager.getInstance().isFirstBotEntry(entry)) {
            BotManager.after(BotManager.randMs(500, 900),
                    () -> BotManager.getInstance().botReply(entry, "whisper me navi stuff, it's private"));
        }
    }

    /** The words after navi, without leading or trailing punctuation. */
    static String requestOf(String message) {
        Matcher m = NAVI_PATTERN.matcher(message == null ? "" : message.strip());
        return m.matches() ? m.group(1).replaceAll("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$", "") : "";
    }

    /** Whisper entry point. The caller has already checked the speaker is the registered owner.
     *  Command patterns first; a request none of them match goes to {@link NaviRouter}, and a route that
     *  changes a bot (rescue, park) waits for {@code navi yes}. */
    static void handleWhisper(BotEntry entry, Character owner, String message) {
        String request = requestOf(message);
        if (CONFIRM_PATTERN.matcher(request).matches()) {
            String command = takePending(owner.getId(), System.currentTimeMillis());
            if (command == null || !dispatch(entry, owner, command)) {
                whisperLater(entry.bot, owner, List.of("nothing is waiting for a yes"), BotManager.randMs(400, 800));
            }
            return;
        }
        if (dispatch(entry, owner, request)) {
            return;
        }
        if (BotFleetOps.isHelp(request) || !NaviRouter.available()) {
            whisperLater(entry.bot, owner, List.of(BotFleetOps.HELP_LINE), BotManager.randMs(400, 800));
            return;
        }
        routeLater(entry, owner, request);
    }

    /** Runs {@code request} when a command pattern matches it; false when none does. */
    private static boolean dispatch(BotEntry entry, Character owner, String request) {
        Character bot = entry.bot;
        // LLM serving stats are the game server's own measurements; no service call either.
        if (isLlmRequest(request)) {
            whisperLater(bot, owner, server.bots.llm.LlmStats.report(System.currentTimeMillis()),
                    BotManager.randMs(400, 800));
            return true;
        }
        // Fleet ops answer from the game server's own state; no service call, no token needed.
        List<String> fleet = BotFleetOps.handle(owner, request);
        if (fleet != null) {
            whisperLater(bot, owner, fleet.stream().limit(MAX_LINES).toList(), BotManager.randMs(400, 800));
            return true;
        }
        String tool = toolForRequest(request);
        if (tool == null) {
            return false;
        }
        callService(bot, owner, tool);
        return true;
    }

    /** Asks the model which tool {@code request} means, on the router's own thread: a model call never
     *  waits behind bot chatter for a permit, and bot chatter never takes the owner's. */
    private static void routeLater(BotEntry entry, Character owner, String request) {
        Character bot = entry.bot;
        if (!inflight.add(owner.getId())) {
            whisperLater(bot, owner, List.of("still checking, one sec"), BotManager.randMs(300, 600));
            return;
        }
        AtomicBoolean done = new AtomicBoolean(false);
        BotManager.after(NaviRouter.ACK_AFTER_MS, () -> {
            if (!done.get()) {
                whisper(bot, owner, BotManager.randomReply(ACKS));
            }
        });
        ROUTER.execute(() -> {
            NaviRouter.Route route = null;
            try {
                route = NaviRouter.route(request);
            } catch (Throwable t) {
                log.warn("navi route failed: {}", t.getClass().getSimpleName());
            } finally {
                done.set(true);
                inflight.remove(owner.getId());
            }
            // Request text is the owner's own; log only what the model chose.
            log.info("navi route for {} -> {}", owner.getName(), route == null ? "no route" : route.command());
            NaviRouter.Route chosen = route;
            BotManager.after(0, () -> deliver(entry, owner, chosen));
        });
    }

    private static void deliver(BotEntry entry, Character owner, NaviRouter.Route route) {
        Character bot = entry.bot;
        if (route == null || route.none()) {
            whisperLater(bot, owner, List.of(BotFleetOps.HELP_LINE), BotManager.randMs(300, 600));
        } else if (route.missingBot()) {
            whisperLater(bot, owner, List.of("which bot? like: navi " + route.tool() + " 26"),
                    BotManager.randMs(300, 600));
        } else if (route.changesState()) {
            pending.put(owner.getId(), new Pending(route.command(), System.currentTimeMillis() + CONFIRM_TTL_MS));
            whisperLater(bot, owner, List.of(route.command() + "? whisper navi yes within a minute to go ahead"),
                    BotManager.randMs(300, 600));
        } else if (!dispatch(entry, owner, route.command())) {
            whisperLater(bot, owner, List.of(BotFleetOps.HELP_LINE), BotManager.randMs(300, 600));
        }
    }

    /** A routed command waiting for {@code navi yes}. */
    record Pending(String command, long expiresAtMs) {
    }

    /** The owner's waiting command, removed; null when there is none or it expired. */
    static String takePending(int ownerId, long nowMs) {
        Pending p = pending.remove(ownerId);
        return p != null && nowMs <= p.expiresAtMs() ? p.command() : null;
    }

    private static void callService(Character bot, Character owner, String tool) {
        if (TOKEN.isEmpty()) {
            whisperLater(bot, owner, List.of("navi isn't set up on this server yet (no NAVI_TOKEN)"),
                    BotManager.randMs(400, 800));
            return;
        }
        if (!inflight.add(owner.getId())) {
            whisperLater(bot, owner, List.of("still checking, one sec"), BotManager.randMs(300, 600));
            return;
        }
        whisperLater(bot, owner, List.of(BotManager.randomReply(ACKS)), BotManager.randMs(400, 900));

        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT + tool))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + TOKEN)
                // The service decides whose mailbox this is; owning a bot isn't enough.
                .header("X-Navi-Owner", owner.getName())
                .GET()
                .build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    inflight.remove(owner.getId());
                    if (error != null) {
                        log.info("navi {} for {} failed: {}", tool, owner.getName(), error.getClass().getSimpleName());
                    } else {
                        log.info("navi {} for {} -> HTTP {}", tool, owner.getName(), response.statusCode());
                    }
                    List<String> lines = error != null
                            ? resultLines(0, null, error)
                            : resultLines(response.statusCode(), response.body(), null);
                    whisperLater(bot, owner, lines, BotManager.randMs(600, 1200));
                });
    }

    static void whisperLater(Character bot, Character owner, List<String> lines, long firstDelayMs) {
        long delay = firstDelayMs;
        for (String line : lines) {
            BotManager.after(delay, () -> whisper(bot, owner, line));
            delay += BotManager.randMs(700, 1300);
        }
    }

    private static void whisper(Character bot, Character owner, String text) {
        if (owner.getClient() == null || !owner.isLoggedinWorld() || bot.getClient() == null) {
            return;
        }
        owner.sendPacket(PacketCreator.getWhisperReceive(
                bot.getName(), bot.getClient().getChannel() - 1, false, BotManager.sanitizeChat(text)));
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
