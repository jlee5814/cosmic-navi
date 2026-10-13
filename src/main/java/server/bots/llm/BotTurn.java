package server.bots.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.bots.BotChatManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads an owner's chat line no command matched and names the state question it asks, if any
 * ({@link BotChatManager#stateQuestions}), so code answers it with the command's own report: "how rich are
 * you" gets the same exact reply as "mesos", and the model never writes the number.
 *
 * A call of its own, apart from the reply: it sees only the line, never the persona or the game state. On
 * Qwen3-4B one call that wrote the reply and named the question tagged nearly every chat line as a question
 * ("gg" asked where, 38 of 72 right); this call alone got 19 of 24 lines it had not seen, with one chat line
 * tagged (tools/llm-bench/bot_eval/state_questions.py). A line that names no question, or a miss, goes on to
 * the normal reply.
 *
 * The output is constrained by a JSON schema (SGLang grammar; on MLX the server needs
 * {@code --mlx-enable-sampling}). A server that refuses {@code response_format} (HTTP 400) turns the call off
 * for the process.
 */
final class BotTurn {
    private static final Logger log = LoggerFactory.getLogger(BotTurn.class);

    private static final int MAX_TOKENS = 24;

    /** A word on what a question means where its name alone is unclear. */
    private static final Map<String, String> HINTS = Map.of(
            "where", "where they are",
            "doing", "what they are doing",
            "slots", "free inventory slots");

    /** Examples in the prompt; every label is a state question (checked by a test). None is a test line. */
    static final List<String[]> EXAMPLES = List.of(
            new String[] {"nice", null}, new String[] {"hows the money", "mesos"}, new String[] {"omw", null},
            new String[] {"u at 70 yet", "level"}, new String[] {"wanna duo?", null},
            new String[] {"u in town?", "where"}, new String[] {"any hp pots?", "pots"},
            new String[] {"hows ur inv space", "slots"}, new String[] {"what u doing", "doing"},
            new String[] {"how strong is ur luk", "stats"});

    // The sender talking about themselves ("im lv 50 now") asks the bot nothing, whatever the model says.
    private static final Pattern SPEAKS_OF_SELF = Pattern.compile("\\b(?:i|im|i'm|ive|i've|my|me|mine)\\b");
    private static final Pattern ADDRESSES_BOT = Pattern.compile("\\b(?:u|you|ur|your|ya|yours|r)\\b");

    private static volatile boolean structuredOff = false;

    private BotTurn() {
    }

    /** The state question {@code message} asks the bot, or null for chat (and when the call fails). */
    static String classify(String message) {
        if (!BotLlmConfig.isOpenAi() || structuredOff || message == null || message.isBlank()) {
            return null;
        }
        List<String> questions = BotChatManager.stateQuestions();
        List<ChatMessage> chat = List.of(new ChatMessage("system", prompt(questions)), new ChatMessage("user", message));
        OpenAiChatClient.Structured out = OpenAiChatClient.sendChatStructured(chat, MAX_TOKENS,
                BotLlmConfig.requestTimeoutMs, schema(questions));
        if (out.formatRejected()) {
            structuredOff = true;
            log.warn("llm: the server refused response_format; state questions go to the normal reply");
            return null;
        }
        return out.content() == null ? null : parse(out.content(), message, questions);
    }

    /** The named question when the model says the line asks one and the line does not just talk about the
     *  sender; null otherwise. */
    static String parse(String content, String message, List<String> questions) {
        if (!content.replace(" ", "").contains("\"fact_question\":true")) {
            return null;
        }
        String ask = OllamaClient.extractStringField(content, "ask", 0);
        if (ask == null || !questions.contains(ask)) {
            return null;
        }
        String m = message.toLowerCase(java.util.Locale.ROOT);
        return SPEAKS_OF_SELF.matcher(m).find() && !ADDRESSES_BOT.matcher(m).find() ? null : ask;
    }

    static String schema(List<String> questions) {
        StringBuilder names = new StringBuilder("\"chat\"");
        for (String q : questions) {
            names.append(",\"").append(OllamaClient.jsonEscape(q)).append('"');
        }
        return "{\"type\":\"object\",\"properties\":{"
                + "\"fact_question\":{\"type\":\"boolean\"},"
                + "\"ask\":{\"type\":\"string\",\"enum\":[" + names + "]}},"
                + "\"required\":[\"fact_question\",\"ask\"],\"additionalProperties\":false}";
    }

    static String prompt(List<String> questions) {
        List<String> named = new ArrayList<>(questions.size());
        for (String q : questions) {
            named.add(HINTS.getOrDefault(q, q));
        }
        StringBuilder examples = new StringBuilder();
        for (String[] e : EXAMPLES) {
            examples.append(' ').append(e[0]).append(" -> ")
                    .append(e[1] == null ? "false, chat." : "true, " + e[1] + '.');
        }
        return "You read chat messages in an online game. A player sent the message below to a friend. "
                + "fact_question: true only if the message asks the friend for a fact about their own character right now "
                + "(" + String.join(", ", named) + "). "
                + "Reactions, greetings, plans, invitations, news about the sender and small talk are false. "
                + "ask: the fact it asks for, or chat when fact_question is false. Examples:" + examples;
    }
}
