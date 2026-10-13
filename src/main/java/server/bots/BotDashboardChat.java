package server.bots;

import client.Character;
import server.bots.llm.BotLlmConfig;
import server.bots.llm.BotLlmReplyManager;
import server.bots.llm.SenderRelation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chat with a bot from the dashboard (/api/chat). The dashboard speaks as the bot's owner, the same as an
 * owner whisper: the message goes through {@link BotChatManager#handleChat} (commands) and, when no
 * command matches, the LLM reply. Replies come back on {@link ReplyChannel#DASHBOARD} into a short
 * per-bot log the page polls, so nothing is said in the game. Like every channel, it stays the bot's
 * reply channel until the owner next talks to it in game.
 */
final class BotDashboardChat {
    private static final int MAX_LINES = 40;

    record Line(long t, boolean fromBot, String text) {
    }

    private static final Map<Integer, Deque<Line>> LOG = new ConcurrentHashMap<>();

    private BotDashboardChat() {
    }

    static void send(BotEntry entry, String message) {
        record(entry.bot.getId(), false, message);
        entry.replyChannel = ReplyChannel.DASHBOARD;
        BotChatManager.handleChat(entry, message);
        if (BotLlmConfig.enabled && !BotChatManager.wasLastChatHandled()) {
            BotLlmReplyManager.respond(entry, SenderRelation.OWNER, ownerName(entry), message);
        }
    }

    static void reply(BotEntry entry, String text) {
        record(entry.bot.getId(), true, text);
    }

    private static void record(int botId, boolean fromBot, String text) {
        Deque<Line> lines = LOG.computeIfAbsent(botId, k -> new ArrayDeque<>());
        synchronized (lines) {
            lines.addLast(new Line(System.currentTimeMillis(), fromBot, text));
            while (lines.size() > MAX_LINES) {
                lines.removeFirst();
            }
        }
    }

    /** The registered owner's name when there is one (roster bots own themselves), else the active owner's. */
    private static String ownerName(BotEntry entry) {
        Integer ownerId = BotOwnershipService.getInstance().getRegisteredOwnerId(entry.bot.getId());
        String name = ownerId == null ? null : Character.getNameById(ownerId);
        if (name != null) {
            return name;
        }
        Character owner = entry.getOwner();
        return owner != null && owner != entry.bot ? owner.getName() : "owner";
    }

    /** {@code {"lines":[{"t","bot","m"}]}}, oldest first. */
    static String json(int botId) {
        Deque<Line> lines = LOG.get(botId);
        StringBuilder sb = new StringBuilder("{\"lines\":[");
        if (lines != null) {
            synchronized (lines) {
                boolean first = true;
                for (Line l : lines) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    sb.append("{\"t\":").append(l.t()).append(",\"bot\":").append(l.fromBot())
                            .append(",\"m\":").append(BotWorldGraphWebServer.jsonStr(l.text())).append('}');
                }
            }
        }
        return sb.append("]}").toString();
    }
}
