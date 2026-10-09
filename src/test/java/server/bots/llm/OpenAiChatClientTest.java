package server.bots.llm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiChatClientTest {
    private String backend, endpoint, model;
    private int maxConcurrent;
    private boolean enabled, shared;

    @BeforeEach
    void save() {
        backend = BotLlmConfig.backend;
        endpoint = BotLlmConfig.endpoint;
        model = BotLlmConfig.model;
        maxConcurrent = BotLlmConfig.maxConcurrentGlobal;
        enabled = BotLlmConfig.enabled;
        shared = BotLlmConfig.sharedPrefixLayout;
    }

    @AfterEach
    void restore() {
        BotLlmConfig.backend = backend;
        BotLlmConfig.endpoint = endpoint;
        BotLlmConfig.model = model;
        BotLlmConfig.maxConcurrentGlobal = maxConcurrent;
        BotLlmConfig.enabled = enabled;
        BotLlmConfig.sharedPrefixLayout = shared;
    }

    @Test
    void bodyCarriesBothTurnsTheTokenCapAndNoThinking() {
        BotLlmConfig.model = "default";
        String body = OpenAiChatClient.buildBody("Sipsaeki: \"yo\"\nSipsBuddy1:", "be brief", 24);
        assertTrue(body.contains("\"messages\":[{\"role\":\"system\",\"content\":\"be brief\"},"
                + "{\"role\":\"user\",\"content\":\"Sipsaeki: \\\"yo\\\"\\nSipsBuddy1:\"}]"), body);
        assertTrue(body.contains("\"max_tokens\":24"), body);
        assertTrue(body.contains("\"stream\":false"), body);
        assertTrue(body.contains("\"chat_template_kwargs\":{\"enable_thinking\":false}"), body);
    }

    @Test
    void contentIsReadFromTheFirstChoiceMessage() {
        String json = "{\"id\":\"x\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":\"ya just \\\"grinding\\\"\\nlol\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":300}}";
        assertEquals("ya just \"grinding\"\nlol", OpenAiChatClient.extractContent(json));
    }

    @Test
    void nullOrMissingContentYieldsNull() {
        assertNull(OpenAiChatClient.extractContent("{\"choices\":[{\"message\":{\"content\":null}}]}"));
        assertNull(OpenAiChatClient.extractContent("{\"error\":{\"message_text\":\"bad\"}}"));
        assertNull(OpenAiChatClient.extractContent(null));
    }

    @Test
    void envPointsBotChatAtAnOpenAiServer() {
        BotLlmConfig.applyEnv(Map.of(
                "BOT_LLM_ENABLED", "true",
                "BOT_LLM_BACKEND", "OpenAI",
                "BOT_LLM_ENDPOINT", "http://host.docker.internal:30000/",
                "BOT_LLM_MAX_CONCURRENT", "8"));
        assertTrue(BotLlmConfig.enabled);
        assertTrue(BotLlmConfig.isOpenAi());
        assertEquals("http://host.docker.internal:30000", BotLlmConfig.endpoint);
        assertEquals("default", BotLlmConfig.model);
        assertEquals(8, BotLlmConfig.maxConcurrentGlobal);
    }

    @Test
    void malformedEnvKeepsDefaults() {
        BotLlmConfig.applyEnv(Map.of("BOT_LLM_MAX_CONCURRENT", "lots"));
        assertEquals(maxConcurrent, BotLlmConfig.maxConcurrentGlobal);
    }

    @Test
    void sharedLayoutKeepsTheBotIdentityOutOfTheSystemPrompt() {
        BotLlmConfig.sharedPrefixLayout = true;
        String a = PromptBuilder.buildSystem(null, SenderRelation.OWNER, "Sipsaeki");
        String b = PromptBuilder.buildSystem(null, SenderRelation.OWNER, "superman");
        assertFalse(a.contains("Your IGN"), a);
        assertTrue(a.startsWith("You are a real human MapleStory player. Talk like mmo chatter"), a);
        int shared = 0;
        while (shared < Math.min(a.length(), b.length()) && a.charAt(shared) == b.charAt(shared)) shared++;
        assertTrue(shared > 300, "rules should form a long common prefix, got " + shared);
    }
}
