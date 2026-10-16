package server.bots;

import client.Character;
import client.inventory.Equip;
import constants.game.ExpTable;
import server.ItemInformationProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a gear detour pays: the hours of grinding with the piece that win back the experience the detour costs
 * ({@link BotGrindAdvisor#paybackHours}) against the hours the bot will wear it. Wear time is the levels until a
 * better obtainable piece of the same kind becomes wearable for the bot's job (the gear reigns, {@link
 * BotGearMetaReport#candidates}), at the bot's current leveling rate on its grind map. Cheap pieces repay in
 * minutes and pass; a long farm passes only for a piece worn for many levels; a boss passes once the bot kills it
 * fast. The SSOT for gear detours: the shop's gear trip and the craft errand both ask {@link #judge}.
 */
final class BotGearPayback {
    private BotGearPayback() {}

    /** The verdict on one detour. Infinite payback when the piece doesn't level the bot faster. */
    record Verdict(double detourHours, double expPerHour, double expPerHourWith, double paybackHours,
                   double wearHours) {
        boolean worth() {
            return paybackHours < wearHours;
        }
    }

    static final Verdict NO_PLAN = new Verdict(0, 0, 0, Double.POSITIVE_INFINITY, 0);

    // A piece nothing obtainable replaces is worn to here at most.
    private static final int LAST_LEVEL = 200;

    /** Per (job, kind): the obtainable pieces sorted by level requirement, scored for that job. Static data. */
    private static final Map<Long, List<BotGearMetaReport.Candidate>> TABLES = new ConcurrentHashMap<>();

    private static List<BotGearMetaReport.Candidate> table(ItemInformationProvider ii, int jobId, int kind) {
        return TABLES.computeIfAbsent(((long) jobId << 32) | kind, k -> {
            List<BotGearMetaReport.Candidate> c = new ArrayList<>(BotGearMetaReport.candidates(ii, jobId, kind));
            c.sort(Comparator.comparingInt(BotGearMetaReport.Candidate::reqLevel));
            return List.copyOf(c);
        });
    }

    /** The first level above {@code fromLevel} at which a better obtainable piece than {@code score} can be worn;
     *  {@link #LAST_LEVEL} when none. Pure over the table. */
    static int replacedAt(List<BotGearMetaReport.Candidate> sortedByReq, double score, int fromLevel) {
        for (BotGearMetaReport.Candidate c : sortedByReq) {
            if (c.reqLevel() > fromLevel && c.score() > score) {
                return c.reqLevel();
            }
        }
        return LAST_LEVEL;
    }

    /** The level a piece is worn to: where a better obtainable one can be worn, or, when the table has none, the
     *  fleet's outgrown line ({@link BotStorageMuleManager#DEMAND_OUTGROWN_LEVELS} past its requirement), so a
     *  piece nothing listed replaces isn't counted as worn to the level cap. */
    static int wornUntil(int replacedAt, int reqLevel) {
        return replacedAt < LAST_LEVEL ? replacedAt : reqLevel + BotStorageMuleManager.DEMAND_OUTGROWN_LEVELS;
    }

    /** Hours to go from {@code fromLevel} to {@code toLevel} at {@code expPerHour}. */
    static double hoursToLevel(int fromLevel, int toLevel, double expPerHour) {
        if (expPerHour <= 0) {
            return 0;
        }
        double exp = 0;
        for (int lv = Math.max(1, fromLevel); lv < Math.min(toLevel, LAST_LEVEL); lv++) {
            exp += ExpTable.getExpNeededForLevel(lv);
        }
        return exp / expPerHour;
    }

    /**
     * The verdict on getting {@code clean} (catalog stats) with a detour of {@code detourHours}, for a gain of
     * {@code gain} in the grind planner's units ({@link BotGrindAdvisor#catalogAcquireGain}). {@code grind} is the
     * bot's grind map candidate (its plan); {@link #NO_PLAN} without one.
     */
    static Verdict judge(Character bot, ItemInformationProvider ii, Equip clean, double gain, double detourHours,
                         BotGrindPlanner.MobCandidate grind) {
        if (grind == null || gain <= 0) {
            return NO_PLAN;
        }
        double worn = Math.max(1.0, BotGrindAdvisor.totalWornValue(bot, ii));
        double exp0 = grind.exp() * BotGrindPlanner.killsPerHour(grind);
        double exp1 = BotGrindAdvisor.expPerHourWithDpsGain(grind, gain / worn);
        return new Verdict(detourHours, exp0, exp1, BotGrindAdvisor.paybackHours(detourHours, exp0, exp1),
                hoursToLevel(bot.getLevel(), wornUntilLevel(bot, ii, clean), exp0));
    }

    /** The level {@code piece} stays worn to for this bot: where a better obtainable piece of its kind can be worn,
     *  else the fleet's outgrown line past its requirement. Shared by gear detours and scroll pricing. */
    static int wornUntilLevel(Character bot, ItemInformationProvider ii, Equip piece) {
        int jobId = bot.getJob() == null ? 0 : bot.getJob().getId();
        Integer req = ii.getEquipLevelReq(piece.getItemId());
        return wornUntil(replacedAt(table(ii, jobId, piece.getItemId() / 10000),
                BotScrollManager.potentialValue(jobId, ii, piece), bot.getLevel()), req == null ? 0 : req);
    }

    /** Round trip travel hours from where the bot stands to {@code mapId}, from one flood of the world graph. */
    static double tripHours(Map<Integer, Double> floodSeconds, int mapId) {
        Double oneWay = floodSeconds.get(mapId);
        return (oneWay == null ? BotTravelCost.HORIZON_SECONDS : oneWay * 2.0) / 3600.0;
    }
}
