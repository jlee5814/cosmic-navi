package server.bots.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Client for OpenAI compatible servers (SGLang, vLLM, llama.cpp server): POST /v1/chat/completions,
 * non-streaming. Same contract as {@link OllamaClient}: hard timeout, and every failure returns
 * Optional.empty so the game loop never sees an exception.
 */
public final class OpenAiChatClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiChatClient.class);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(5000))
            .build();

    private OpenAiChatClient() {}

    static Optional<String> send(String prompt, String system, int maxTokens, int timeoutMs) {
        java.util.List<ChatMessage> messages = new java.util.ArrayList<>(2);
        if (system != null && !system.isEmpty()) messages.add(new ChatMessage("system", system));
        messages.add(new ChatMessage("user", prompt));
        return sendChat(messages, maxTokens, timeoutMs);
    }

    static Optional<String> sendChat(java.util.List<ChatMessage> messages, int maxTokens, int timeoutMs) {
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(BotLlmConfig.endpoint + "/v1/chat/completions"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildBody(messages, maxTokens)))
                    .build();
        } catch (Exception e) {
            log.warn("openai: request build failed: {}", e.toString());
            return Optional.empty();
        }
        try {
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("openai: HTTP {} body: {}", resp.statusCode(), abbrev(resp.body(), 200));
                return Optional.empty();
            }
            String text = extractContent(resp.body());
            if (text == null || text.isBlank()) return Optional.empty();
            return Optional.of(text.trim());
        } catch (java.net.http.HttpTimeoutException te) {
            log.info("openai: timeout after {}ms", timeoutMs);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("openai: send failed: {}", e.toString());
            return Optional.empty();
        }
    }

    static String buildBody(String prompt, String system, int maxTokens) {
        java.util.List<ChatMessage> messages = new java.util.ArrayList<>(2);
        if (system != null && !system.isEmpty()) messages.add(new ChatMessage("system", system));
        messages.add(new ChatMessage("user", prompt));
        return buildBody(messages, maxTokens);
    }

    static String buildBody(java.util.List<ChatMessage> messages, int maxTokens) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"model\":\"").append(OllamaClient.jsonEscape(BotLlmConfig.model)).append("\",");
        sb.append("\"stream\":false,\"messages\":[");
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"role\":\"").append(OllamaClient.jsonEscape(m.role()))
                    .append("\",\"content\":\"").append(OllamaClient.jsonEscape(m.content())).append("\"}");
        }
        sb.append("],");
        sb.append("\"max_tokens\":").append(maxTokens)
                .append(",\"temperature\":").append(BotLlmConfig.temperature)
                .append(",\"top_p\":").append(BotLlmConfig.topP)
                .append(",\"top_k\":").append(BotLlmConfig.topK)
                .append(",\"min_p\":").append(BotLlmConfig.minP)
                .append(",\"presence_penalty\":").append(BotLlmConfig.presencePenalty)
                .append(",\"repetition_penalty\":").append(BotLlmConfig.repeatPenalty);
        if (BotLlmConfig.disableThinking) {
            sb.append(",\"chat_template_kwargs\":{\"enable_thinking\":false}");
        }
        sb.append('}');
        return sb.toString();
    }

    /** choices[0].message.content from a non-streaming reply; null when absent or JSON null. */
    static String extractContent(String json) {
        if (json == null) return null;
        int msg = json.indexOf("\"message\"");
        if (msg < 0) return null;
        return OllamaClient.extractStringField(json, "content", msg);
    }

    private static String abbrev(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
