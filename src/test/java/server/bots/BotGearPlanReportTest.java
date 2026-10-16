package server.bots;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotGearPlanReportTest {
    private static BotGearPlanReport.Pick pick(int id, double gain, double hours, double payback) {
        return new BotGearPlanReport.Pick(id, 10, gain, new BotScrollManager.Acquisition("farm", hours * 3_600_000.0),
                0, payback);
    }

    @Test
    void picksRankByPaybackThenGain() {
        var zakum = pick(1, 30.0, 300, 900);          // a big gain that takes 900 hours of grinding to repay
        var bathrobe = pick(2, 5.7, 0.01, 0.05);      // repaid in three minutes
        var shield = pick(3, 4.4, 1.8, 12);
        var oneShot = pick(4, 9.0, 1, Double.POSITIVE_INFINITY); // already one hit kills: levels no faster
        List<BotGearPlanReport.Pick> picks = new ArrayList<>(List.of(zakum, oneShot, shield, bathrobe));
        picks.sort(BotGearPlanReport.BEST_FIRST);
        assertEquals(List.of(2, 3, 1, 4), picks.stream().map(BotGearPlanReport.Pick::itemId).toList());
    }

    @Test
    void moreDamageLevelsFasterUntilOneHitKillsThenNotAtAll() {
        var slow = new BotGrindPlanner.MobCandidate(1, "mob", 30, 100, 4.0, 1, "map", 40, List.of());
        double exp0 = slow.exp() * BotGrindPlanner.killsPerHour(slow);
        double exp1 = BotGrindAdvisor.expPerHourWithDpsGain(slow, 1.0); // twice the damage: half the kill time
        assertTrue(exp1 > exp0, exp0 + " -> " + exp1);
        var oneHit = new BotGrindPlanner.MobCandidate(1, "mob", 30, 100, 0.72, 1, "map", 40, List.of());
        assertEquals(oneHit.exp() * BotGrindPlanner.killsPerHour(oneHit), BotGrindAdvisor.expPerHourWithDpsGain(oneHit, 1.0), 1e-9);

        assertEquals(10.0, BotGrindAdvisor.paybackHours(1.0, 1000, 1100), 1e-9); // 1,000 exp lost, 100 an hour back
        assertTrue(Double.isInfinite(BotGrindAdvisor.paybackHours(1.0, 1000, 1000)));
    }
}
