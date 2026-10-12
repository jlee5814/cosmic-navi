package server.bots;

import constants.game.ExpTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.life.Monster;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BotGrindTargetLogTest {
    private final double explore = BotCombatManager.cfg.GRIND_TARGET_EXPLORE;
    private final int band = BotCombatManager.cfg.GRIND_TARGET_EXPLORE_BAND_MS;

    @AfterEach
    void restore() {
        BotCombatManager.cfg.GRIND_TARGET_EXPLORE = explore;
        BotCombatManager.cfg.GRIND_TARGET_EXPLORE_BAND_MS = band;
    }

    private static BotCombatManager.ScoredGrindTarget candidate(long cost) {
        return new BotCombatManager.ScoredGrindTarget(mock(Monster.class), cost, 0, 0, 1, 1);
    }

    @Test
    void picksTheRuleChoiceWhenExplorationIsOff() {
        BotCombatManager.cfg.GRIND_TARGET_EXPLORE = 0.0;
        List<BotCombatManager.ScoredGrindTarget> ranking = List.of(candidate(1000), candidate(1100));

        BotGrindTargetLog.Pick pick = BotGrindTargetLog.choose(ranking, true);

        assertEquals(0, pick.index());
        assertEquals(1.0, pick.probability());
    }

    @Test
    void exploresOnlyNearEqualAlternativesOnAFreshPick() {
        BotCombatManager.cfg.GRIND_TARGET_EXPLORE = 1.0;
        BotCombatManager.cfg.GRIND_TARGET_EXPLORE_BAND_MS = 500;
        List<BotCombatManager.ScoredGrindTarget> ranking =
                List.of(candidate(1000), candidate(1400), candidate(9000));

        BotGrindTargetLog.Pick fresh = BotGrindTargetLog.choose(ranking, true);
        BotGrindTargetLog.Pick held = BotGrindTargetLog.choose(ranking, false);

        assertEquals(1, fresh.index(), "the 9000 ms candidate is outside the band");
        assertEquals(1.0, fresh.probability());
        assertEquals(0, held.index(), "a bot with a live target never explores");
    }

    @Test
    void neverExploresOffTheGraph() {
        BotCombatManager.cfg.GRIND_TARGET_EXPLORE = 1.0;
        List<BotCombatManager.ScoredGrindTarget> ranking = List.of(
                new BotCombatManager.ScoredGrindTarget(mock(Monster.class), 10, 10, 0),
                new BotCombatManager.ScoredGrindTarget(mock(Monster.class), 12, 12, 0));

        BotGrindTargetLog.Pick pick = BotGrindTargetLog.choose(ranking, true);

        assertEquals(0, pick.index(), "rule points are not milliseconds; the band does not apply");
        assertEquals(1.0, pick.probability());
    }

    @Test
    void noPickWithoutCandidates() {
        assertNull(BotGrindTargetLog.choose(List.of(), true));
    }

    @Test
    void countsExpAcrossLevelUps() {
        assertEquals(500, BotGrindTargetLog.expGained(30, 1000, 30, 1500));
        long need30 = ExpTable.getExpNeededForLevel(30);
        assertEquals(need30 - 1000 + 200, BotGrindTargetLog.expGained(30, 1000, 31, 200));
        assertTrue(BotGrindTargetLog.expGained(31, 0, 30, 0) == 0, "a level drop (death penalty) earns nothing");
    }
}
