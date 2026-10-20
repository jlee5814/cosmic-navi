package server.bots.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Names the Navi tool an owner's whisper asks for when no command pattern matched it ("navi did anyone
 * email me" is email, "navi whats 26 up to" is why 26). The model only picks: the answer is a tool from a
 * closed list plus an optional bot name, under a JSON schema, turned into the exact command the patterns
 * already handle ({@link Route#command}). Ownership, name resolution and the work itself stay in code.
 *
 * <p>A call of its own, like {@link BotTurn}: it sees only the request, never game state or mail. On MLX the
 * server needs {@code --mlx-enable-sampling} for the schema; a server that refuses {@code response_format}
 * (HTTP 400) turns routing off for the process. Phrasing accuracy is measured against the live model by
 * {@code tools/llm-bench/bot_eval/navi_route.py}.
 */
public final class NaviRouter {
    private static final Logger log = LoggerFactory.getLogger(NaviRouter.class);

    /** Each tool and what the prompt tells the model it does. "none" is the answer for everything else. */
    static final String[][] TOOLS = {
            {"email", "a summary of their own email inbox"},
            {"fleet", "how all of their bots are doing"},
            {"why", "what one bot is doing and why"},
            {"rescue", "get one stuck bot unstuck"},
            {"park", "log one bot out to rest"},
            {"llm", "how fast the language model is running"},
            {"none", "anything else: chat, and requests no tool covers"}};

    static final Set<String> NEEDS_BOT = Set.of("why", "rescue", "park");
    static final Set<String> CHANGES_STATE = Set.of("rescue", "park");

    /** Prompt examples: request, tool, bot. None is an eval line (checked by navi_route.py). */
    static final String[][] EXAMPLES = {
            {"any new mail", "email", ""}, {"how is everyone", "fleet", ""},
            {"what is SipsBuddy7 up to", "why", "SipsBuddy7"}, {"31 wont move, fix him", "rescue", "31"},
            {"send 5 to bed", "park", "5"}, {"is the model lagging", "llm", ""},
            {"email my mom", "none", ""}, {"thanks", "none", ""}, {"rescue me lol", "none", ""}};

    /** Whisper an ack when routing takes longer than this, so the owner knows Navi heard. Set it from the
     *  structured p99 that navi llm reports: below it most owners never see the ack. */
    public static final long ACK_AFTER_MS = 1000;

    static final int MAX_TOKENS = 32;

    private static volatile boolean structuredOff = false;

    private NaviRouter() {
    }

    /** The tool a request asks for, as the command to run. {@code bot} is empty when the tool takes none. */
    public record Route(String tool, String bot) {
        public boolean none() {
            return "none".equals(tool);
        }

        public boolean missingBot() {
            return NEEDS_BOT.contains(tool) && bot.isEmpty();
        }

        public boolean changesState() {
            return CHANGES_STATE.contains(tool);
        }

        /** The request the command patterns handle: "email", "why 26". */
        public String command() {
            return bot.isEmpty() ? tool : tool + " " + bot;
        }
    }

    /** True when routing can run: OpenAI compatible chat is on and the server took the schema so far. */
    public static boolean available() {
        return BotLlmConfig.enabled && BotLlmConfig.isOpenAi() && !structuredOff;
    }

    /** Blocking model call; run it off the game thread. Null when the call fails or the answer is unusable. */
    public static Route route(String request) {
        if (!available() || request == null || request.isBlank()) {
            return null;
        }
        List<ChatMessage> chat = List.of(new ChatMessage("system", prompt()), new ChatMessage("user", request));
        OpenAiChatClient.Structured out = OpenAiChatClient.sendChatStructured(chat, MAX_TOKENS,
                BotLlmConfig.requestTimeoutMs, schema(), "navi_route");
        if (out.formatRejected()) {
            structuredOff = true;
            log.warn("navi: the server refused response_format; unmatched navi requests get the help line");
            return null;
        }
        return out.content() == null ? null : parse(out.content());
    }

    /** The route in the model's JSON, or null when the tool isn't one of {@link #TOOLS}. The bot keeps only
     *  its last word, the shape the command patterns take ("bot 26" becomes 26). */
    static Route parse(String content) {
        String tool = OllamaClient.extractStringField(content, "tool", 0);
        if (tool == null) {
            return null;
        }
        tool = tool.strip().toLowerCase(Locale.ROOT);
        if (!toolNames().contains(tool)) {
            return null;
        }
        String bot = "";
        if (NEEDS_BOT.contains(tool)) {
            String raw = OllamaClient.extractStringField(content, "bot", 0);
            String[] words = (raw == null ? "" : raw.replaceAll("[^\\p{Alnum}\\s]", " ").strip()).split("\\s+");
            bot = words[words.length - 1];
        }
        return new Route(tool, bot);
    }

    public static List<String> toolNames() {
        List<String> names = new ArrayList<>(TOOLS.length);
        for (String[] t : TOOLS) {
            names.add(t[0]);
        }
        return names;
    }

    static String schema() {
        StringBuilder names = new StringBuilder();
        for (String t : toolNames()) {
            names.append(names.length() == 0 ? "" : ",").append('"').append(t).append('"');
        }
        return "{\"type\":\"object\",\"properties\":{"
                + "\"tool\":{\"type\":\"string\",\"enum\":[" + names + "]},"
                + "\"bot\":{\"type\":\"string\"}},"
                + "\"required\":[\"tool\",\"bot\"],\"additionalProperties\":false}";
    }

    static String prompt() {
        StringBuilder tools = new StringBuilder();
        for (String[] t : TOOLS) {
            tools.append(' ').append(t[0]).append(": ").append(t[1]).append('.');
        }
        StringBuilder examples = new StringBuilder();
        for (String[] e : EXAMPLES) {
            examples.append(' ').append(e[0]).append(" -> ").append(e[1])
                    .append(e[2].isEmpty() ? "" : " " + e[2]).append('.');
        }
        return "A player in an online game whispered the message below to Navi, their own assistant. "
                + "Pick the one tool it asks for." + tools
                + " bot: the bot name or number the message names, for why, rescue and park; empty otherwise."
                + " Examples:" + examples;
    }
}
