package server.bots.llm;

import client.Character;
import constants.game.ExpTable;
import org.junit.jupiter.api.Test;
import server.bots.BotChatManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Naming an owner's state question ({@link BotTurn}), and the guard on chat replies ({@link ReplyGuard}). */
class BotTurnTest {
    private static final List<String> QUESTIONS = List.of("where", "level", "mesos");

    @Test
    void aFactQuestionIsNamedAndChatIsNot() {
        assertEquals("mesos", BotTurn.parse("{\"fact_question\": true, \"ask\": \"mesos\"}", "u rich?", QUESTIONS));
        assertNull(BotTurn.parse("{\"fact_question\": false, \"ask\": \"chat\"}", "gg", QUESTIONS));
        assertNull(BotTurn.parse("{\"fact_question\": false, \"ask\": \"where\"}", "gg", QUESTIONS),
                "the yes or no decides");
        assertNull(BotTurn.parse("{\"fact_question\": true, \"ask\": \"trade\"}", "trade me", QUESTIONS),
                "only listed questions");
        assertNull(BotTurn.parse("{\"fact_question\": true, \"ask\": \"level\"}", "im lv 50 now", QUESTIONS),
                "the sender's own news asks the bot nothing");
        assertEquals("level", BotTurn.parse("{\"fact_question\": true, \"ask\": \"level\"}", "my bad, ur lvl?",
                QUESTIONS));
        assertNull(BotTurn.parse("{\"fact_question\": tr", "u rich?", QUESTIONS), "cut off JSON names nothing");
    }

    @Test
    void theSchemaAndPromptCoverEveryQuestion() {
        String schema = BotTurn.schema(QUESTIONS);
        assertTrue(schema.contains("\"enum\":[\"chat\",\"where\",\"level\",\"mesos\"]"), schema);
        assertTrue(schema.contains("\"required\":[\"fact_question\",\"ask\"]"), schema);
        assertTrue(BotTurn.prompt(QUESTIONS).contains("(where they are, level, mesos)"), BotTurn.prompt(QUESTIONS));
        for (String[] e : BotTurn.EXAMPLES) {
            assertTrue(e[1] == null || BotChatManager.stateQuestions().contains(e[1]), e[0] + " -> " + e[1]);
        }

        String body = OpenAiChatClient.buildBody(List.of(new ChatMessage("user", "how rich r u")), 24,
                "{\"type\":\"json_schema\"}", 0.0);
        assertTrue(body.endsWith(",\"response_format\":{\"type\":\"json_schema\"}}"), body);
        assertTrue(body.contains("\"temperature\":0.0"), body);
        assertFalse(OpenAiChatClient.buildBody(List.of(new ChatMessage("user", "hi")), 24, null)
                .contains("response_format"));
    }

    @Test
    void theGuardCatchesAWrongLevelExpOrMesosClaimAndPassesTheRest() {
        Character bot = mock(Character.class);
        when(bot.getLevel()).thenReturn(70);
        when(bot.getExp()).thenReturn(ExpTable.getExpNeededForLevel(70) / 4); // 25%
        when(bot.getMeso()).thenReturn(19_021_749);

        assertEquals("level", ReplyGuard.contradiction(bot, "hows it going", "im lv 66 grinding"));
        assertEquals("level", ReplyGuard.contradiction(bot, "lvl?", "lv66 lol"));
        assertNull(ReplyGuard.contradiction(bot, "hows it going", "im lv70 grinding"));
        assertNull(ReplyGuard.contradiction(bot, "when 2nd job", "you need level 30 for that"), "not a claim about me");

        assertEquals("level", ReplyGuard.contradiction(bot, "close to leveling?", "nah only 60% exp"));
        assertNull(ReplyGuard.contradiction(bot, "close to leveling?", "nah only 25% exp"));
        assertNull(ReplyGuard.contradiction(bot, "close to leveling?", "75% left till lv71"), "the remainder reading");
        assertNull(ReplyGuard.contradiction(bot, "how was the boss", "it had like 10% hp left"), "no exp talk");

        assertEquals("mesos", ReplyGuard.contradiction(bot, "how rich are you", "got like 500k"));
        assertEquals("mesos", ReplyGuard.contradiction(bot, "sup", "i have 2,000 mesos lol"));
        assertNull(ReplyGuard.contradiction(bot, "how rich are you", "got like 19m"));
        assertNull(ReplyGuard.contradiction(bot, "sup", "i have 19,021,749 mesos"));
        assertNull(ReplyGuard.contradiction(bot, "pots?", "i have 3 pots"), "no meso claim");
        assertNull(ReplyGuard.contradiction(bot, "price?", "red pots are 50 mesos each"), "a price, not my mesos");
    }

    @Test
    void amountsReadGroupedAndCompactForms() {
        assertEquals(19_021_749d, ReplyGuard.amount("19,021,749", null));
        assertEquals(1_200_000d, ReplyGuard.amount("1.2", "m"), 0.5);
        assertEquals(1_500_000d, ReplyGuard.amount("1,5", "m"), 0.5);
        assertEquals(500_000d, ReplyGuard.amount("500", "k"));
    }
}
