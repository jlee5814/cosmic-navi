package server.bots;

import client.Character;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotDashboardChatTest {
    @Test
    void anOwnerReplyOnTheDashboardChannelLandsInTheChatLogNotInGame() {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(910_777);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.replyChannel = ReplyChannel.DASHBOARD;

        BotManager.getInstance().botReply(entry, "farming boars, want anything?");

        String json = BotDashboardChat.json(910_777);
        assertTrue(json.contains("\"bot\":true,\"m\":\"farming boars, want anything?\""), json);
        assertEquals("{\"lines\":[]}", BotDashboardChat.json(910_778), "each bot has its own log");
    }

    @Test
    void theLogKeepsTheLastFortyLines() {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(910_779);
        BotEntry entry = new BotEntry(bot, null, null);
        for (int i = 0; i < 45; i++) {
            BotDashboardChat.reply(entry, "line " + i);
        }
        String json = BotDashboardChat.json(910_779);
        assertTrue(json.contains("line 5\"") && json.contains("line 44\"") && !json.contains("line 4\""), json);
    }
}
