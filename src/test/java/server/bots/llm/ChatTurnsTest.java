package server.bots.llm;

import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;
import server.bots.BotEntry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatTurnsTest {

    private static BotEntry bot() {
        Character c = mock(Character.class);
        when(c.getName()).thenReturn("SipsBuddy1");
        when(c.getJob()).thenReturn(Job.IL_WIZARD);
        when(c.getLevel()).thenReturn(50);
        when(c.getMeso()).thenReturn(19_021_749);
        BotEntry entry = mock(BotEntry.class);
        when(entry.getBot()).thenReturn(c);
        return entry;
    }

    @Test
    void historyBecomesRealTurnsAndTheNewestLineIsAPlainUserMessage() {
        List<BotMemoryStore.Turn> recent = List.of(
                new BotMemoryStore.Turn(1L, "owner", "Sipsaeki", "what level are you", "50 rn"));
        List<ChatMessage> chat = PromptBuilder.buildChat(bot(), SenderRelation.OWNER, "Sipsaeki",
                "can you give me a party invite", "", recent);

        assertEquals(List.of("system", "user", "assistant", "user"), chat.stream().map(ChatMessage::role).toList());
        assertTrue(chat.get(0).content().startsWith("You are a real human MapleStory player. Talk like mmo chatter"));
        assertTrue(chat.get(0).content().contains("Your IGN is SipsBuddy1"));
        assertEquals("what level are you", chat.get(1).content());
        assertEquals("50 rn", chat.get(2).content());
        // The newest turn carries the live game state after the history, then the line itself.
        assertTrue(chat.get(3).content().endsWith("can you give me a party invite"), chat.get(3).content());
        // No transcript labels anywhere for the model to imitate.
        assertFalse(chat.stream().anyMatch(m -> m.content().contains("SipsBuddy1:") || m.content().contains("Sipsaeki:")));
    }

    @Test
    void jobNamesAreSpelledOutWithTheirClass() {
        assertEquals("ice/lightning wizard (magician class)", PromptBuilder.jobLabel(Job.IL_WIZARD));
        assertEquals("fire/poison archmage (magician class)", PromptBuilder.jobLabel(Job.FP_ARCHMAGE));
        assertEquals("hermit (thief class)", PromptBuilder.jobLabel(Job.HERMIT));
        assertEquals("warrior", PromptBuilder.jobLabel(Job.WARRIOR));
    }

    @Test
    void theNewestTurnStatesLevelAndJobNextToTheQuestion() {
        List<ChatMessage> chat = PromptBuilder.buildChat(bot(), SenderRelation.OWNER, "Sipsaeki",
                "what job are you", "", List.of());
        assertTrue(chat.get(chat.size() - 1).content().contains("Job: ice/lightning wizard (magician class)"),
                chat.get(chat.size() - 1).content());
        assertTrue(chat.get(chat.size() - 1).content().contains("Mesos: 19,021,749"), chat.get(chat.size() - 1).content());
    }

    @Test
    void bodyCarriesEveryTurnInOrder() {
        String body = OpenAiChatClient.buildBody(List.of(
                new ChatMessage("system", "rules"),
                new ChatMessage("user", "hi"),
                new ChatMessage("assistant", "yo"),
                new ChatMessage("user", "pt?")), 24);
        assertTrue(body.contains("\"messages\":[{\"role\":\"system\",\"content\":\"rules\"},"
                + "{\"role\":\"user\",\"content\":\"hi\"},{\"role\":\"assistant\",\"content\":\"yo\"},"
                + "{\"role\":\"user\",\"content\":\"pt?\"}]"), body);
    }

    @Test
    void echoesOfTheSendersLineAreCaught() {
        assertTrue(BotLlmReplyManager.isEchoOf("can you give me a party invite", "can you give me a party invite?"));
        assertTrue(BotLlmReplyManager.isEchoOf("can you give me a party invite", "sipsaeki can you give me a party invite."));
        assertTrue(BotLlmReplyManager.isEchoOf("what level are you", "what level r you"));
        assertFalse(BotLlmReplyManager.isEchoOf("can you give me a party invite", "cant rn, grinding teddies"));
        assertFalse(BotLlmReplyManager.isEchoOf("what level are you", "50, almost 51"));
        assertFalse(BotLlmReplyManager.isEchoOf("hi", "hi"), "a greeting back is not an echo");
    }

    @Test
    void speakerLabelsForEitherNameAreStripped() {
        assertEquals("hows the grind?", BotLlmReplyManager.stripSpeakerLabel("sipsbuddy1: hows the grind?", "SipsBuddy1", "Sipsaeki"));
        assertEquals("can you give me a party invite", BotLlmReplyManager.stripSpeakerLabel("sipsaeki : can you give me a party invite", "SipsBuddy1", "Sipsaeki"));
        assertEquals("sipsbuddy1 is grinding", BotLlmReplyManager.stripSpeakerLabel("sipsbuddy1 is grinding", "SipsBuddy1", "Sipsaeki"));
    }
}
