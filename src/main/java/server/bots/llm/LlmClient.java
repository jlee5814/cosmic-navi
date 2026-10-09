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

    private static Optional<String> send(String prompt, String system, int maxTokens, int timeoutMs) {
        return BotLlmConfig.isOpenAi()
                ? OpenAiChatClient.send(prompt, system, maxTokens, timeoutMs)
                : OllamaClient.send(prompt, system, maxTokens, timeoutMs);
    }
}
