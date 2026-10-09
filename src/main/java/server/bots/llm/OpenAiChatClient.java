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
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(BotLlmConfig.endpoint + "/v1/chat/completions"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildBody(prompt, system, maxTokens)))
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
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"model\":\"").append(OllamaClient.jsonEscape(BotLlmConfig.model)).append("\",");
        sb.append("\"stream\":false,\"messages\":[");
        if (system != null && !system.isEmpty()) {
            sb.append("{\"role\":\"system\",\"content\":\"").append(OllamaClient.jsonEscape(system)).append("\"},");
        }
        sb.append("{\"role\":\"user\",\"content\":\"").append(OllamaClient.jsonEscape(prompt)).append("\"}],");
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
