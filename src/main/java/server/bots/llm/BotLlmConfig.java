package server.bots.llm;

import java.util.Map;

public final class BotLlmConfig {
    public static volatile boolean enabled = false;

    // "ollama" (/api/generate) or "openai" (/v1/chat/completions: SGLang, vLLM, llama.cpp server).
    public static volatile String backend = "ollama";
    public static volatile boolean typoSuggesterEnabled = false; // recommended off if LLM on; it can block casual chat

    public static volatile String endpoint = "http://localhost:11434";
    public static volatile String model = "qwen3.5:0.8b";
    public static volatile int requestTimeoutMs = 15_000;
    public static volatile int maxConcurrentGlobal = 1;

    public static int maxReplyChars() {
        return Math.max(1, maxReplyMessages) * Math.max(1, maxReplyCharsPerMessage);
    }

    // CPU cap: how many threads Ollama may use per inference. 0 lets Ollama decide.
    public static volatile int numThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

    // Keep the model resident in Ollama after each call to avoid cold-load cost.
    public static volatile String keepAlive = "30m";

    // Qwen-family models can emit thinking tokens. Disable them for one-line MMO chatter.
    public static volatile boolean disableThinking = true;

    // Short cosmetic replies should finish quickly and never compete with gameplay.
    public static volatile int maxPredictTokens = 24;
    public static volatile int numCtx = 4096;
    public static volatile int recentTurnsInPrompt = 3;

    // Qwen3.5 standard non-thinking sampler, kept tight for short chat.
    public static volatile double temperature = 1.0;
    public static volatile double topP = 0.95;
    public static volatile int topK = 20;
    public static volatile double minP = 0.0;
    public static volatile double presencePenalty = 1.5;
    public static volatile double repeatPenalty = 1.0;

    // Keep cosmetic chatter to one in-game line by default.
    public static volatile int maxReplyMessages = 1;
    public static volatile int multiMessageDelayMs = 1800;
    public static volatile int maxReplyCharsPerMessage = 120;

    // Shared rules open the system prompt and the bot's identity moves into the user turn, so every
    // bot's prompt starts with the same tokens and a prefix cache (SGLang RadixAttention) reuses them.
    public static volatile boolean sharedPrefixLayout = true;

    public static volatile boolean debugLog = false;

    // Persistent memory is optional. Leave it off for the lightest setup; enable
    // only if bot chatter needs to remember past conversations across server restarts.
    public static volatile boolean recentMemoryEnabled = true;
    public static volatile int recentMemoryMaxTurns = 4;
    public static volatile long recentMemoryMaxAgeMs = 15L * 60L * 1000L;
    public static volatile boolean memoryEnabled = false;
    public static volatile String memoryDir = "bots/llm-memory";
    public static volatile int compactBatchSize = 8;
    public static volatile int summaryMaxPredictTokens = 300;

    static {
        applyEnv(System.getenv());
    }

    /**
     * Startup overrides, so a deployment can point bot chat at a server without code changes:
     * BOT_LLM_ENABLED, BOT_LLM_BACKEND, BOT_LLM_ENDPOINT, BOT_LLM_MODEL, BOT_LLM_MAX_CONCURRENT.
     * Unset or malformed values keep the defaults above.
     */
    static void applyEnv(Map<String, String> env) {
        String v = env.get("BOT_LLM_ENABLED");
        if (v != null && !v.isBlank()) enabled = v.equalsIgnoreCase("true") || v.equals("1");
        v = env.get("BOT_LLM_BACKEND");
        if (v != null && !v.isBlank()) backend = v.trim().toLowerCase(java.util.Locale.ROOT);
        v = env.get("BOT_LLM_ENDPOINT");
        if (v != null && !v.isBlank()) endpoint = v.trim().replaceAll("/+$", "");
        v = env.get("BOT_LLM_MODEL");
        if (v != null && !v.isBlank()) model = v.trim();
        else if (isOpenAi()) model = "default";
        v = env.get("BOT_LLM_MAX_CONCURRENT");
        if (v != null && !v.isBlank()) {
            try {
                maxConcurrentGlobal = Math.max(1, Integer.parseInt(v.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    public static boolean isOpenAi() {
        return "openai".equals(backend);
    }

    private BotLlmConfig() {}
}
