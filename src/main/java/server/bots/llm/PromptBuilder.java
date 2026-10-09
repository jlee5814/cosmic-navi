package server.bots.llm;

import client.Character;
import server.bots.BotEntry;

import java.util.ArrayList;
import java.util.List;

public final class PromptBuilder {
    private PromptBuilder() {}

    private static final String RULES =
            "Talk like mmo chatter: lowercase, 1 short sentence, abbreviate whenever possible, text only, use internet slang when applicable, no emoji, no speaker label. "
            + "Keep replies simple. Prefer fragments over full grammar. "
            + "Answer only the latest message directly. If unsure, say so briefly instead of guessing. "
            + "Never say you are an ai, assistant, chatbot, or bot. ";

    /**
     * With {@link BotLlmConfig#sharedPrefixLayout} on, the system prompt holds only what every bot
     * shares, and the bot's identity opens the user turn (see {@link #buildPrompt}), so prompts
     * from different bots share a prefix the server can cache.
     */
    public static String buildSystem(BotEntry entry, SenderRelation relation, String senderName) {
        String relationLine = switch (relation) {
            case OWNER -> "You are talking to your close gaming friend and party leader " + senderName + ". Be friendly and helpful.";
            case PARTY -> "You are talking to " + senderName + ", your party member. Be casual.";
            case STRANGER -> "You are talking to " + senderName + ", a stranger you just met in the game.";
        };
        if (BotLlmConfig.sharedPrefixLayout) {
            return "You are a real human MapleStory player. " + RULES + relationLine;
        }
        return identity(entry) + RULES + relationLine;
    }

    static String identity(BotEntry entry) {
        Character bot = entry.getBot();
        String botName = bot != null ? bot.getName() : "bot";
        String job = bot != null ? jobLabel(bot.getJob()) : "adventurer";
        int lvl = bot != null ? bot.getLevel() : 1;
        return "Your IGN is " + botName + ". "
                + "You are a real human MapleStory player, level " + lvl + " " + job + ". ";
    }

    /**
     * A job name a model can read: "IL_WIZARD" becomes "ice/lightning wizard (magician class)". The raw
     * enum ("il wizard") meant nothing to Qwen3-1.7B, which answered "warrior" when asked its job.
     */
    static String jobLabel(client.Job job) {
        if (job == null) return "adventurer";
        String name = job.name().toLowerCase(java.util.Locale.ROOT).replaceAll("[0-9]", "").replace('_', ' ').trim();
        name = name.replaceFirst("^fp ", "fire/poison ").replaceFirst("^il ", "ice/lightning ");
        String family = switch ((job.getId() / 100) % 10) {
            case 1 -> "warrior";
            case 2 -> "magician";
            case 3 -> "bowman";
            case 4 -> "thief";
            case 5 -> "pirate";
            default -> null;
        };
        return family == null || name.contains(family) ? name : name + " (" + family + " class)";
    }

    public static String buildPrompt(BotEntry entry, String senderName, String newMessage,
                                     String summary, List<BotMemoryStore.Turn> recent) {
        StringBuilder sb = new StringBuilder(512);
        if (BotLlmConfig.sharedPrefixLayout) {
            sb.append(identity(entry)).append('\n');
        }
        if (summary != null && !summary.isBlank()) {
            sb.append("What you remember: ").append(summary).append("\n\n");
        }
        String situation = SituationBuilder.build(entry);
        if (!situation.isEmpty()) {
            sb.append(situation).append('\n');
        }
        if (recent != null && !recent.isEmpty()) {
            sb.append("Recent chat (older lines matter less):\n");
            String botName = entry.getBot() != null ? entry.getBot().getName() : "bot";
            long now = System.currentTimeMillis();
            for (BotMemoryStore.Turn t : recent) {
                String age = SituationBuilder.ago(now - t.ts());
                sb.append('[').append(age).append(" ago] ")
                        .append(t.sender()).append(": ").append(t.msg()).append('\n');
                sb.append(botName).append(": ").append(t.reply()).append('\n');
            }
            sb.append('\n');
        }
        sb.append("Reply to the newest message only. Treat older chat as background, not the topic.\n");
        sb.append(senderName).append(": ").append(newMessage).append('\n');
        sb.append(entry.getBot() != null ? entry.getBot().getName() : "bot").append(':');
        return sb.toString();
    }

    /**
     * The same conversation as {@link #buildSystem} plus {@link #buildPrompt}, but as chat messages
     * for OpenAI compatible servers: earlier exchanges become real user and assistant turns, and the
     * newest line is a user message. A small model then knows which lines it said and answers, instead
     * of continuing a pasted transcript (which made it echo the sender or speak for them).
     *
     * The live game state rides in the newest turn, after the history: placed before it, a wrong
     * earlier reply ("7% left") outweighed the state in tests, even with an instruction to trust the
     * state; placed after it, Qwen3-1.7B read the state correctly 8 times out of 8. The shared rules
     * still open the system prompt, so the server's prefix cache keeps reusing them.
     */
    public static List<ChatMessage> buildChat(BotEntry entry, SenderRelation relation, String senderName,
                                              String newMessage, String summary,
                                              List<BotMemoryStore.Turn> recent) {
        StringBuilder sys = new StringBuilder(768);
        sys.append(buildSystem(entry, relation, senderName));
        sys.append("\n\n").append(identity(entry).trim());
        if (summary != null && !summary.isBlank()) {
            sys.append("\nWhat you remember: ").append(summary.trim());
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", sys.toString()));
        if (recent != null) {
            for (BotMemoryStore.Turn t : recent) {
                if (t.msg() == null || t.reply() == null || t.msg().isBlank() || t.reply().isBlank()) continue;
                messages.add(new ChatMessage("user", t.msg()));
                messages.add(new ChatMessage("assistant", t.reply()));
            }
        }
        String situation = (SituationBuilder.build(entry) + SituationBuilder.buildForQuestion(entry, newMessage)).trim();
        messages.add(new ChatMessage("user", situation.isEmpty()
                ? newMessage
                : "[your game state right now]\n" + situation + "\n\n" + newMessage));
        return messages;
    }
}
