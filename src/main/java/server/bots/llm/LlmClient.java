package server.bots.llm;

import java.util.Optional;

/** Entry point for bot LLM calls; routes to the configured backend (see {@link BotLlmConfig#backend}). */
public final class LlmClient {
    private LlmClient() {}

    public static Optional<String> generate(String prompt, String system) {
        return send(prompt, system, BotLlmConfig.maxPredictTokens, BotLlmConfig.requestTimeoutMs);
    }

    /** Non-chat calls (memory summaries): a bigger token budget and a proportionally longer timeout. */
    public static Optional<String> generateLong(String prompt, String system, int numPredict) {
        // Rough scale: a small CPU produces ~10 tok/s, so allow numPredict*200ms + base.
        int timeoutMs = Math.max(BotLlmConfig.requestTimeoutMs, 5000 + numPredict * 200);
        return send(prompt, system, numPredict, timeoutMs);
    }

    /**
     * A chat request as role-tagged messages. OpenAI compatible servers get the messages as they are;
     * Ollama's /api/generate takes one prompt, so the turns are flattened into the system prompt and
     * the newest user message.
     */
    public static Optional<String> chat(java.util.List<ChatMessage> messages) {
        if (BotLlmConfig.isOpenAi()) {
            return OpenAiChatClient.sendChat(messages, BotLlmConfig.maxPredictTokens, BotLlmConfig.requestTimeoutMs);
        }
        StringBuilder system = new StringBuilder();
        String last = "";
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (i == messages.size() - 1 && "user".equals(m.role())) {
                last = m.content();
            } else {
                if (system.length() > 0) system.append('\n');
                system.append("system".equals(m.role()) ? "" : m.role() + ": ").append(m.content());
            }
        }
        return send(last, system.toString(), BotLlmConfig.maxPredictTokens, BotLlmConfig.requestTimeoutMs);
    }

    private static Optional<String> send(String prompt, String system, int maxTokens, int timeoutMs) {
        return BotLlmConfig.isOpenAi()
                ? OpenAiChatClient.send(prompt, system, maxTokens, timeoutMs)
                : OllamaClient.send(prompt, system, maxTokens, timeoutMs);
    }
}
