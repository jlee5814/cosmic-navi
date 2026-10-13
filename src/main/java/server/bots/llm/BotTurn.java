package server.bots.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.bots.BotChatManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One model call for a chat line no command matched. The model reads the wording and returns JSON
 * {@code {"ask","reply"}}: {@code ask} names a question game state answers exactly
 * ({@link BotChatManager#stateQuestions}), which code then answers with the command's own report, or is
 * {@code none} and {@code reply} carries the chat line. So "how rich are you" gets the same exact answer as
 * "mesos" without a phrase for every wording, and the model never writes the number.
 *
 * The output is constrained by a JSON schema (SGLang grammar; on MLX the server needs
 * {@code --mlx-enable-sampling}). A server that refuses {@code response_format} (HTTP 400) turns structured
 * turns off for the process, and replies fall back to free text.
 */
final class BotTurn {
    private static final Logger log = LoggerFactory.getLogger(BotTurn.class);

    /** Tokens the JSON wrapper costs on top of the reply budget ({"ask":"none","reply":"..."}). */
    private static final int JSON_OVERHEAD_TOKENS = 16;

    /** A word on what a question means where its name alone is unclear to a small model. */
    private static final Map<String, String> HINTS = Map.of(
            "where", "where (the map you are in)",
            "doing", "doing (what you are up to right now)",
            "damage", "damage (your range or attack)",
            "slots", "slots (free inventory space)",
            "pots", "pots (potions you carry)");

    private static volatile boolean structuredOff = false;

    record Result(String ask, String reply) {
    }

    private BotTurn() {
    }

    /** The model's turn for {@code chat}; empty when the server gave nothing back. */
    static Optional<Result> take(List<ChatMessage> chat) {
        if (!BotLlmConfig.isOpenAi() || structuredOff) {
            return LlmClient.chat(chat).map(text -> new Result(null, text));
        }
        List<String> questions = BotChatManager.stateQuestions();
        OpenAiChatClient.Structured out = OpenAiChatClient.sendChatStructured(withRule(chat, questions),
                BotLlmConfig.maxPredictTokens + JSON_OVERHEAD_TOKENS, BotLlmConfig.requestTimeoutMs, schema(questions));
        if (out.formatRejected()) {
            structuredOff = true;
            log.warn("llm: the server refused response_format; chat falls back to free text replies");
            return LlmClient.chat(chat).map(text -> new Result(null, text));
        }
        return out.content() == null ? Optional.empty() : Optional.of(parse(out.content(), questions));
    }

    /** The question name when it is one of {@code questions}, and the reply when it is not blank. Text that
     *  is not JSON at all (a server ignoring the schema) is taken as the reply. */
    static Result parse(String content, List<String> questions) {
        String ask = OllamaClient.extractStringField(content, "ask", 0);
        String reply = OllamaClient.extractStringField(content, "reply", 0);
        if (ask == null && reply == null && !content.trim().startsWith("{")) {
            reply = content;
        }
        return new Result(ask != null && questions.contains(ask) ? ask : null,
                reply == null || reply.isBlank() ? null : reply);
    }

    static String schema(List<String> questions) {
        StringBuilder names = new StringBuilder("\"none\"");
        for (String q : questions) {
            names.append(",\"").append(OllamaClient.jsonEscape(q)).append('"');
        }
        return "{\"type\":\"object\",\"properties\":{"
                + "\"ask\":{\"type\":\"string\",\"enum\":[" + names + "]},"
                + "\"reply\":{\"type\":\"string\",\"maxLength\":" + BotLlmConfig.maxReplyChars() + "}},"
                + "\"required\":[\"ask\",\"reply\"],\"additionalProperties\":false}";
    }

    static String rule(List<String> questions) {
        List<String> named = new ArrayList<>(questions.size());
        for (String q : questions) {
            named.add(HINTS.getOrDefault(q, q));
        }
        return "Answer as JSON with ask and reply. If the newest message asks for one of these facts about you, "
                + "set ask to its name and reply to an empty string: " + String.join(", ", named) + ". "
                + "Otherwise set ask to none and put your chat line in reply.";
    }

    /** {@code chat} with the JSON rule closing the system message (the shared rules still open it, so the
     *  server's prefix cache keeps reusing them). */
    private static List<ChatMessage> withRule(List<ChatMessage> chat, List<String> questions) {
        List<ChatMessage> out = new ArrayList<>(chat);
        String rule = rule(questions);
        if (!out.isEmpty() && "system".equals(out.get(0).role())) {
            out.set(0, new ChatMessage("system", out.get(0).content() + "\n" + rule));
        } else {
            out.add(0, new ChatMessage("system", rule));
        }
        return out;
    }
}
