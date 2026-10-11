package server.bots.llm;

import client.Character;
import constants.game.ExpTable;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import server.bots.BotEntry;
import server.life.Monster;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Builds a short "current situation" snapshot inlined into the LLM prompt so
 * bots can answer where/what/who questions accurately instead of hallucinating.
 * Queried fresh at every call — never cached. All reads are best-effort; any
 * null/missing piece is silently skipped so a partial state still produces a
 * useful block.
 */
public final class SituationBuilder {
    private SituationBuilder() {}

    public static String build(BotEntry entry) {
        if (entry == null || entry.getBot() == null) return "";
        Character bot = entry.getBot();
        MapleMap map = bot.getMap();
        StringBuilder sb = new StringBuilder(256);
        sb.append("[Where you are now]\n");

        if (map != null) {
            String name = map.getMapName();
            String street = map.getStreetName();
            if (name != null && !name.isBlank()) {
                sb.append("Map: ").append(name);
                if (street != null && !street.isBlank() && !street.equalsIgnoreCase(name)) {
                    sb.append(" (").append(street).append(')');
                }
                sb.append('\n');
            }
        }

        sb.append("Status: ").append(describeActivity(entry)).append('\n');

        int lvl = bot.getLevel();
        int pct = expPercent(bot, lvl);
        // A labeled "Job:" line: folded into the level line, small models missed it ("what job are you"
        // got "warrior" or "shaman"); Qwen3-4B answered correctly 8/8 with this line.
        sb.append("Job: ").append(PromptBuilder.jobLabel(bot.getJob())).append('\n');
        sb.append("Level ").append(lvl);
        // Spelled out both ways: "66% to next" read as either progress or remainder, and bots answered
        // "how much exp left" with the wrong one.
        if (pct >= 0) {
            sb.append(", EXP ").append(pct).append("% (").append(100 - pct).append("% left until level ")
                    .append(lvl + 1).append(')');
        }
        sb.append('\n');

        String mobs = describeMobs(map);
        if (!mobs.isEmpty()) sb.append("Mobs around: ").append(mobs).append('\n');

        String party = describeParty(bot);
        if (!party.isEmpty()) sb.append("Party: ").append(party).append('\n');

        if (entry.lastOwnerCommand != null && !entry.lastOwnerCommand.isBlank()) {
            sb.append("Last command from owner: \"").append(entry.lastOwnerCommand).append('"')
                    .append(" (").append(ago(System.currentTimeMillis() - entry.lastOwnerCommandAtMs))
                    .append(" ago)\n");
        }
        return sb.toString();
    }

    private static String describeActivity(BotEntry entry) {
        if (entry.isGrinding()) {
            MapleMap m = entry.getBot().getMap();
            if (entry.getFarmAnchor() != null && m != null && entry.getFarmAnchorMapId() == m.getId()) {
                return "grinding (camping this spot)";
            }
            return "grinding";
        }
        if (entry.isFollowing()) return "following owner";
        return "standing around, no orders";
    }

    private static int expPercent(Character bot, int lvl) {
        try {
            if (lvl >= 200) return -1;
            int needed = ExpTable.getExpNeededForLevel(lvl);
            if (needed <= 0) return -1;
            long pct = (100L * Math.max(0, bot.getExp())) / needed;
            return (int) Math.min(99, pct);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String describeMobs(MapleMap map) {
        if (map == null) return "";
        // Preserve discovery order so the same map produces stable text.
        LinkedHashMap<String, int[]> counts = new LinkedHashMap<>();
        try {
            for (Monster mob : map.getAllMonsters()) {
                if (mob == null || !mob.isAlive()) continue;
                String key = mob.getName() + "|" + mob.getLevel();
                counts.computeIfAbsent(key, k -> new int[1])[0]++;
            }
        } catch (Throwable t) {
            return "";
        }
        if (counts.isEmpty()) return "";
        // Top 4 by count
        record Entry(String name, int lvl, int count) {}
        java.util.List<Entry> list = new java.util.ArrayList<>(counts.size());
        for (var e : counts.entrySet()) {
            String[] parts = e.getKey().split("\\|", 2);
            int lvl = 0;
            try { lvl = Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {}
            list.add(new Entry(parts[0], lvl, e.getValue()[0]));
        }
        list.sort((a, b) -> Integer.compare(b.count(), a.count()));
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Entry e : list) {
            if (shown == 4) { sb.append(", ..."); break; }
            if (shown > 0) sb.append(", ");
            sb.append(e.name()).append(" lv").append(e.lvl()).append(" x").append(e.count());
            shown++;
        }
        return sb.toString();
    }

    private static String describeParty(Character bot) {
        Party party = bot.getParty();
        if (party == null) return "";
        int myMapId = bot.getMapId();
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (PartyCharacter m : party.getMembers()) {
            if (m == null || m.getId() == bot.getId()) continue;
            if (!first) sb.append(", ");
            sb.append(m.getName());
            if (m.isLeader()) sb.append(" (leader)");
            if (m.getMapId() != myMapId) sb.append(" (elsewhere)");
            first = false;
        }
        return sb.toString();
    }

    /** Compact relative-time string: "12s", "4m", "2h", "3d". */
    public static String ago(long deltaMs) {
        if (deltaMs < 0) deltaMs = 0;
        long s = deltaMs / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m";
        long h = m / 60;
        if (h < 24) return h + "h";
        return (h / 24) + "d";
    }

    // ─── On-demand sections: only when the question asks, so everyday chat stays short ────────────

    private static final java.util.regex.Pattern GEAR_WORDS = java.util.regex.Pattern.compile(
            "\\b(equip\\w*|gear|eq|wear\\w*|weapon|staff|wand|claw|sword|axe|bow|crossbow|gun|knuckle|dagger|"
                    + "spear|polearm|shield|armor|hat|helm\\w*|glove\\w*|shoe\\w*|boots?|overall|top|bottom|cape|earring\\w*|items?)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern SKILL_WORDS = java.util.regex.Pattern.compile(
            "\\b(skills?|sp|max(ed)?|spells?|build|attacks?|spam\\w*|buffs?)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.concurrent.ConcurrentHashMap<Integer, String> SKILL_NAMES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Extra state lines for a question that asks about gear or skills: what the bot actually wears and
     * which skills it has, so the model stops inventing an "iced mage set". Empty for other questions.
     */
    public static String buildForQuestion(BotEntry entry, String question) {
        if (entry == null || entry.getBot() == null || question == null) return "";
        StringBuilder sb = new StringBuilder();
        if (asksAboutGear(question)) {
            String gear = describeGear(entry.getBot());
            if (!gear.isEmpty()) sb.append("Equipped: ").append(gear).append('\n');
        }
        if (asksAboutSkills(question) || mentionsOwnSkill(entry.getBot(), question)) {
            String skills = describeSkills(entry.getBot());
            if (!skills.isEmpty()) sb.append("Skills (level/max): ").append(skills).append('\n');
        }
        return sb.toString();
    }

    static boolean asksAboutGear(String question) {
        return question != null && GEAR_WORDS.matcher(question).find();
    }

    static boolean asksAboutSkills(String question) {
        return question != null && SKILL_WORDS.matcher(question).find();
    }

    static String describeGear(Character bot) {
        try {
            client.inventory.Inventory eq = bot.getInventory(client.inventory.InventoryType.EQUIPPED);
            if (eq == null) return "";
            server.ItemInformationProvider ii = server.ItemInformationProvider.getInstance();
            List<String> names = new ArrayList<>();
            for (client.inventory.Item item : eq.list()) {
                if (item.getPosition() <= -100) continue; // cash cosmetics
                String name = ii.getName(item.getItemId());
                if (name != null && !name.isBlank()) names.add(name);
            }
            return String.join(", ", names);
        } catch (RuntimeException e) {
            return "";
        }
    }

    static String describeSkills(Character bot) {
        try {
            List<String> parts = new ArrayList<>();
            bot.getSkills().entrySet().stream()
                    .filter(e -> e.getValue().skillevel > 0 && !e.getKey().isBeginnerSkill())
                    .sorted((a, b) -> b.getValue().skillevel - a.getValue().skillevel)
                    .limit(8)
                    .forEach(e -> {
                        int max = e.getKey().isFourthJob() ? e.getValue().masterlevel : e.getKey().getMaxLevel();
                        parts.add(skillName(e.getKey().getId()) + " " + e.getValue().skillevel + "/" + max);
                    });
            return String.join(", ", parts);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static boolean mentionsOwnSkill(Character bot, String question) {
        try {
            String q = question.toLowerCase(java.util.Locale.ROOT);
            for (client.Skill skill : bot.getSkills().keySet()) {
                for (String word : skillName(skill.getId()).toLowerCase(java.util.Locale.ROOT).split("\\W+")) {
                    if (word.length() >= 4 && q.contains(word)) return true;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    private static String skillName(int skillId) {
        return SKILL_NAMES.computeIfAbsent(skillId, id -> {
            String n = client.SkillFactory.getSkillName(id);
            return n == null || n.isBlank() ? "skill " + id : n;
        });
    }
}
