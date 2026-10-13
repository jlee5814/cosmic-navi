package server.bots.llm;

import client.Character;
import constants.game.ExpTable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The one model call for unmatched chat: question or reply ({@link BotTurn}), and the guard on replies ({@link ReplyGuard}). */
class BotTurnTest {
    private static final List<String> QUESTIONS = List.of("where", "level", "mesos");

    @Test
    void aNamedQuestionGoesToCodeAndANoneTurnCarriesTheReply() {
        BotTurn.Result asks = BotTurn.parse("{\"ask\":\"mesos\",\"reply\":\"\"}", QUESTIONS);
        assertEquals("mesos", asks.ask());
        assertNull(asks.reply());

        BotTurn.Result chats = BotTurn.parse("{\"ask\":\"none\",\"reply\":\"ya just grinding lol\"}", QUESTIONS);
        assertNull(chats.ask());
        assertEquals("ya just grinding lol", chats.reply());

        assertNull(BotTurn.parse("{\"ask\":\"trade\",\"reply\":\"ok\"}", QUESTIONS).ask(), "only listed questions");
        assertEquals("sure", BotTurn.parse("sure", QUESTIONS).reply(), "a server ignoring the schema still replies");
        assertNull(BotTurn.parse("{\"ask\":\"none\"", QUESTIONS).reply(), "cut off JSON says nothing");
    }

    @Test
    void theSchemaListsEveryQuestionAndCapsTheReply() {
        String schema = BotTurn.schema(QUESTIONS);
        assertTrue(schema.contains("\"enum\":[\"none\",\"where\",\"level\",\"mesos\"]"), schema);
        assertTrue(schema.contains("\"maxLength\":" + BotLlmConfig.maxReplyChars()), schema);
        assertTrue(schema.contains("\"required\":[\"ask\",\"reply\"]"), schema);
        assertTrue(BotTurn.rule(QUESTIONS).contains("where (the map you are in), level, mesos"), BotTurn.rule(QUESTIONS));

        String body = OpenAiChatClient.buildBody(List.of(new ChatMessage("user", "how rich r u")), 40,
                "{\"type\":\"json_schema\"}");
        assertTrue(body.endsWith(",\"response_format\":{\"type\":\"json_schema\"}}"), body);
        assertTrue(!OpenAiChatClient.buildBody(List.of(new ChatMessage("user", "hi")), 24, null)
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
