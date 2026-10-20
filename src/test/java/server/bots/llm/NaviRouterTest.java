package server.bots.llm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NaviRouterTest {
    private boolean enabled;
    private String backend;

    @BeforeEach
    void save() {
        enabled = BotLlmConfig.enabled;
        backend = BotLlmConfig.backend;
    }

    @AfterEach
    void restore() {
        BotLlmConfig.enabled = enabled;
        BotLlmConfig.backend = backend;
    }

    @Test
    void aToolWithABotBecomesTheCommandThePatternsTake() {
        NaviRouter.Route r = NaviRouter.parse("{\"tool\":\"why\",\"bot\":\"SipsBuddy26\"}");
        assertEquals(new NaviRouter.Route("why", "SipsBuddy26"), r);
        assertEquals("why SipsBuddy26", r.command());
        assertEquals("email", NaviRouter.parse("{\"tool\": \"Email\", \"bot\": \"\"}").command());
    }

    @Test
    void theBotKeepsOnlyItsLastWordWithoutPunctuation() {
        assertEquals("26", NaviRouter.parse("{\"tool\":\"rescue\",\"bot\":\"bot 26\"}").bot());
        assertEquals("SipsBuddy7", NaviRouter.parse("{\"tool\":\"park\",\"bot\":\"SipsBuddy7.\"}").bot());
        assertEquals("", NaviRouter.parse("{\"tool\":\"why\",\"bot\":\"  \"}").bot());
    }

    @Test
    void toolsThatTakeNoBotDropOne() {
        assertEquals(new NaviRouter.Route("fleet", ""), NaviRouter.parse("{\"tool\":\"fleet\",\"bot\":\"26\"}"));
    }

    @Test
    void anythingOutsideTheToolListIsNoRoute() {
        assertNull(NaviRouter.parse("{\"tool\":\"send_email\",\"bot\":\"\"}"));
        assertNull(NaviRouter.parse("{\"bot\":\"26\"}"));
        assertNull(NaviRouter.parse("not json"));
    }

    @Test
    void routeFlags() {
        assertTrue(NaviRouter.parse("{\"tool\":\"none\",\"bot\":\"\"}").none());
        assertTrue(new NaviRouter.Route("why", "").missingBot());
        assertFalse(new NaviRouter.Route("fleet", "").missingBot());
        assertTrue(new NaviRouter.Route("rescue", "26").changesState());
        assertTrue(new NaviRouter.Route("park", "26").changesState());
        assertFalse(new NaviRouter.Route("why", "26").changesState());
        assertFalse(new NaviRouter.Route("email", "").changesState());
    }

    @Test
    void schemaClosesTheToolList() {
        String schema = NaviRouter.schema();
        assertTrue(schema.contains("\"enum\":[\"email\",\"fleet\",\"why\",\"rescue\",\"park\",\"llm\",\"none\"]"), schema);
        assertTrue(schema.contains("\"required\":[\"tool\",\"bot\"]"), schema);
        assertTrue(schema.contains("\"additionalProperties\":false"), schema);
    }

    @Test
    void examplesUseRealToolsAndNameABotExactlyWhenTheToolTakesOne() {
        List<String> tools = NaviRouter.toolNames();
        String prompt = NaviRouter.prompt();
        for (String[] e : NaviRouter.EXAMPLES) {
            assertTrue(tools.contains(e[1]), e[0]);
            assertEquals(NaviRouter.NEEDS_BOT.contains(e[1]), !e[2].isEmpty(), e[0]);
            assertTrue(prompt.contains(e[0] + " -> " + e[1]), e[0]);
        }
        for (String t : tools) {
            assertTrue(prompt.contains(" " + t + ": "), t);
        }
    }

    @Test
    void routingNeedsOpenAiChatOn() {
        BotLlmConfig.enabled = false;
        BotLlmConfig.backend = "openai";
        assertFalse(NaviRouter.available());
        assertNull(NaviRouter.route("did anyone email me"));
        BotLlmConfig.enabled = true;
        BotLlmConfig.backend = "ollama";
        assertFalse(NaviRouter.available());
        BotLlmConfig.backend = "openai";
        assertTrue(NaviRouter.available());
        assertNull(NaviRouter.route("   "));
    }
}
