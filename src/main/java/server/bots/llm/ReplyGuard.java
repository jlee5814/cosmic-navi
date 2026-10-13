package server.bots.llm;

import client.Character;
import constants.game.ExpTable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a free chat reply against the bot's own state before it is said. The model sees the state, yet a
 * small one still misreads it ("im lv 66" at 70). A reply that claims the bot's level, exp or mesos must
 * match the character; otherwise the caller answers that question from code instead. Numbers only, not
 * meaning: "ill be rich soon" passes.
 */
final class ReplyGuard {
    // First person level claims: "im lv70", "i am level 70", "my lvl is 70", or a reply opening with "lv70".
    private static final Pattern LEVEL_CLAIM = Pattern.compile(
            "\\b(?:i'?m|i\\s+am)\\s+(?:only\\s+|just\\s+|now\\s+|still\\s+)?(?:lv|lvl|level)\\s*\\.?\\s*(\\d{1,3})\\b"
                    + "|\\bmy\\s+(?:lv|lvl|level)\\s+(?:is\\s+)?(\\d{1,3})\\b"
                    + "|^\\s*(?:lv|lvl|level)\\s*\\.?\\s*(\\d{1,3})\\b");
    private static final Pattern PERCENT = Pattern.compile("(\\d{1,3}(?:\\.\\d+)?)\\s*%");
    private static final Pattern EXP_TALK = Pattern.compile("\\b(?:exp|xp|experience|lv|lvl|level)\\b");
    // First person amounts: "i have 19m", "got like 19,021,749 mesos", "im at 2.3m rn". A price ("pots are
    // 50 mesos") is not a claim about the bot's own mesos.
    private static final Pattern MESO_CLAIM = Pattern.compile(
            "\\b(?:i\\s+(?:have|got)|i'?ve\\s+got|got|have|i'?m\\s+at|sitting\\s+on|holding)\\s+"
                    + "(?:like\\s+|about\\s+|around\\s+|only\\s+|just\\s+|almost\\s+|~)?"
                    + "(\\d+(?:[.,]\\d+)*)\\s*(k|m|mil|b)?\\b\\s*(mesos?\\b)?");
    private static final Pattern MONEY_TALK = Pattern.compile("\\b(?:mesos?|money|cash|rich|broke|bank)\\b");

    /** Exp percent may be off by this many points (rounding, a kill since the state was read). */
    private static final double EXP_SLACK_PCT = 2.0;
    /** Mesos may be off by this fraction (compact amounts like 19m, and "like 20m"). */
    private static final double MESO_SLACK = 0.15;

    private ReplyGuard() {
    }

    /**
     * The state question whose fact {@code reply} gets wrong ({@code "level"} for level or exp,
     * {@code "mesos"}), or null when it states nothing that contradicts the character.
     */
    static String contradiction(Character bot, String message, String reply) {
        if (bot == null || reply == null) {
            return null;
        }
        String r = reply.toLowerCase(Locale.ROOT);
        String m = message == null ? "" : message.toLowerCase(Locale.ROOT);
        int level = bot.getLevel();
        if (claimsOtherLevel(r, level) || claimsOtherExp(r, m, bot.getExp(), level)) {
            return "level";
        }
        if (claimsOtherMesos(r, m, bot.getMeso())) {
            return "mesos";
        }
        return null;
    }

    static boolean claimsOtherLevel(String reply, int level) {
        Matcher mt = LEVEL_CLAIM.matcher(reply);
        while (mt.find()) {
            for (int g = 1; g <= mt.groupCount(); g++) {
                if (mt.group(g) != null && Integer.parseInt(mt.group(g)) != level) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean claimsOtherExp(String reply, String message, int exp, int level) {
        if (!EXP_TALK.matcher(reply).find() && !EXP_TALK.matcher(message).find()) {
            return false;
        }
        int needed = level >= 200 ? 0 : ExpTable.getExpNeededForLevel(level);
        if (needed <= 0) {
            return false;
        }
        double pct = 100.0 * Math.max(0, exp) / needed;
        Matcher mt = PERCENT.matcher(reply);
        while (mt.find()) {
            double claimed = Double.parseDouble(mt.group(1));
            // Either reading is right: "34%" done, or "66% left".
            if (Math.abs(claimed - pct) > EXP_SLACK_PCT && Math.abs(claimed - (100 - pct)) > EXP_SLACK_PCT) {
                return true;
            }
        }
        return false;
    }

    static boolean claimsOtherMesos(String reply, String message, long mesos) {
        boolean moneyTalk = MONEY_TALK.matcher(message).find() || MONEY_TALK.matcher(reply).find();
        Matcher mt = MESO_CLAIM.matcher(reply);
        while (mt.find()) {
            // "i have 3 pots" is no meso claim: it needs the meso word, or a k/m/b amount in money talk.
            boolean aboutMesos = mt.group(3) != null || (mt.group(2) != null && moneyTalk);
            if (aboutMesos && offBy(amount(mt.group(1), mt.group(2)), mesos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean offBy(double claimed, long actual) {
        return Math.abs(claimed - actual) > MESO_SLACK * Math.max(actual, 1);
    }

    /** "19,021,749" and "1.2m" as numbers; a comma before exactly three digits groups thousands. */
    static double amount(String digits, String suffix) {
        String d = digits.matches("\\d{1,3}(,\\d{3})+") ? digits.replace(",", "") : digits.replace(',', '.');
        double v;
        try {
            v = Double.parseDouble(d);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
        if (suffix == null) {
            return v;
        }
        return switch (suffix) {
            case "k" -> v * 1_000d;
            case "m", "mil" -> v * 1_000_000d;
            case "b" -> v * 1_000_000_000d;
            default -> v;
        };
    }
}
