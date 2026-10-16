package server.bots;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotGearPaybackTest {
    private static BotGearMetaReport.Candidate c(int id, int req, double score) {
        return new BotGearMetaReport.Candidate(id, req, score, "shop");
    }

    @Test
    void aPieceIsWornUntilABetterOneCanBeWorn() {
        // Sorted by level: a weaker level 70 piece doesn't end the reign; a stronger level 80 one does.
        List<BotGearMetaReport.Candidate> table = List.of(c(1, 30, 5), c(2, 50, 8), c(3, 70, 7), c(4, 80, 12));
        assertEquals(80, BotGearPayback.replacedAt(table, 8.0, 64));
        assertEquals(50, BotGearPayback.replacedAt(table, 6.0, 40));
        assertEquals(200, BotGearPayback.replacedAt(table, 20.0, 64)); // nothing obtainable beats it
        assertEquals(80, BotGearPayback.replacedAt(table, 8.0, 50)); // only pieces above its level count
    }

    @Test
    void aPieceNothingReplacesIsWornToTheFleetsOutgrownLineNotTheLevelCap() {
        assertEquals(80, BotGearPayback.wornUntil(80, 50)); // the table decides when it can
        assertEquals(26 + BotStorageMuleManager.DEMAND_OUTGROWN_LEVELS, BotGearPayback.wornUntil(200, 26));
        assertEquals(0.0, BotGearPayback.hoursToLevel(67, BotGearPayback.wornUntil(200, 26), 500_000), 1e-9);
    }

    @Test
    void hoursToLevelSumsTheExpTableAtTheRate() {
        double one = constants.game.ExpTable.getExpNeededForLevel(67);
        assertEquals(1.0, BotGearPayback.hoursToLevel(67, 68, one), 1e-9);
        assertEquals(0.0, BotGearPayback.hoursToLevel(67, 67, one), 1e-9);
        assertEquals(0.0, BotGearPayback.hoursToLevel(67, 80, 0), 1e-9); // no grind rate: no wear time
    }

    @Test
    void aDetourIsWorthItWhenItRepaysBeforeThePieceIsReplaced() {
        assertTrue(new BotGearPayback.Verdict(0.2, 500_000, 520_000, 5.0, 40.0).worth());
        assertFalse(new BotGearPayback.Verdict(2.0, 500_000, 515_000, 66.0, 40.0).worth());
        assertFalse(BotGearPayback.NO_PLAN.worth()); // no grind plan: no detour
        assertEquals(0.5, BotGearPayback.tripHours(java.util.Map.of(5, 900.0), 5), 1e-9); // 15 minutes each way
    }
}
