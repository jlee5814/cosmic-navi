package server.bots;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.life.Monster;

import java.awt.Point;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotGrindTargetModelTest {
    private static final Path FIXTURES = Path.of("src", "test", "resources", "learned");
    private final double share = BotCombatManager.cfg.GRIND_TARGET_MODEL_SHARE;
    private final double explore = BotCombatManager.cfg.GRIND_TARGET_EXPLORE;

    @AfterEach
    void restore() {
        BotGrindTargetModel.set(null);
        BotCombatManager.cfg.GRIND_TARGET_MODEL_SHARE = share;
        BotCombatManager.cfg.GRIND_TARGET_EXPLORE = explore;
    }

    @Test
    @SuppressWarnings("unchecked")
    void servedScoresEqualTheTrainersScores() throws IOException {
        // The fixture is the Python trainer's own output and its own scores (tools/learned/train_grind_target.py).
        BotGrindTargetModel.Loaded trees = BotGrindTargetModel.parse(
                Files.readString(FIXTURES.resolve("grind-target-trees-fixture.json")));
        BotGrindTargetModel.Loaded linear = BotGrindTargetModel.parse(
                Files.readString(FIXTURES.resolve("grind-target-linear-fixture.json")));
        assertEquals("trees-fixture", trees.version());
        List<Object> cases = (List<Object>) BotJson.parse(Files.readString(FIXTURES.resolve("grind-target-cases.json")));
        assertTrue(cases.size() > 20);
        for (Object o : cases) {
            Map<String, Object> c = (Map<String, Object>) o;
            List<Object> raw = (List<Object>) c.get("x");
            double[] x = raw.stream().mapToDouble(v -> (Double) v).toArray();
            assertEquals((Double) c.get("trees"), trees.scorer().score(x), 1e-9);
            assertEquals((Double) c.get("linear"), linear.scorer().score(x), 1e-9);
        }
    }

    @Test
    void aModelThatFailedItsGateIsNotServed() {
        assertNull(BotGrindTargetModel.parse("{\"passes_gate\": false, \"kind\": \"trees\"}"));
        assertThrows(IllegalArgumentException.class, () -> BotGrindTargetModel.parse(
                "{\"passes_gate\": true, \"kind\": \"trees\", \"features\": [\"rank\"], \"trees\": []}"));
    }

    @Test
    void theFeaturesAreTheTrainersFromTheSameRoundedNumbers() {
        Character bot = bot(7, new Point(100, 200), 50, 2999, 9000);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.climbing = true;
        Monster mob = mob(new Point(-150, 120), 55, 333, 1000);
        var ranking = List.of(new BotCombatManager.ScoredGrindTarget(mob, 75_000, -1_234, 0, 3, 4));
        // rank, route s (capped at 60), local k, crowd, level gap, mob HP, |dx|/100, dy/100, rope, bot HP (rounded)
        assertArrayEquals(new double[]{0, 60.0, -1.234, 4, 5, 0.333, 2.5, -0.8, 1.0, 0.333},
                BotGrindTargetModel.features(entry, bot, ranking, 0), 1e-12);
    }

    @Test
    void theSplitIsStableAndFollowsTheShare() {
        int in = 0;
        for (int id = 1; id <= 4000; id++) {
            boolean a = BotGrindTargetModel.inModelArm(id, 0.5);
            assertEquals(a, BotGrindTargetModel.inModelArm(id, 0.5));
            in += a ? 1 : 0;
        }
        assertTrue(in > 1800 && in < 2200, "about half: " + in);
        assertTrue(!BotGrindTargetModel.inModelArm(42, 0.0) && BotGrindTargetModel.inModelArm(42, 1.0));
    }

    @Test
    void aModelArmBotTakesTheModelsBestAmongWhatTheRuleAllowsAndLogsItsSupport() {
        BotCombatManager.cfg.GRIND_TARGET_EXPLORE = 0.0; // no exploration, so the pick is the model's best
        BotGrindTargetModel.set(new BotGrindTargetModel.Loaded("trees", "t-1", x -> x[0] == 1 ? 5.0 : 1.0));
        Character bot = bot(7, new Point(0, 0), 50, 1000, 1000);
        BotEntry entry = new BotEntry(bot, null, null);
        var ranking = List.of(
                new BotCombatManager.ScoredGrindTarget(mob(new Point(10, 0), 50, 10, 10), 1000, 1000, 0, 1, 1),
                new BotCombatManager.ScoredGrindTarget(mob(new Point(20, 0), 50, 10, 10), 1400, 1400, 0, 2, 1),
                new BotCombatManager.ScoredGrindTarget(mob(new Point(30, 0), 50, 10, 10), 9000, 9000, 0, 3, 1));

        BotCombatManager.cfg.GRIND_TARGET_MODEL_SHARE = 1.0;
        BotGrindTargetLog.Pick pick = BotGrindTargetLog.choose(entry, bot, ranking, true);
        assertNotNull(pick);
        assertEquals(1, pick.index());
        assertEquals("model", pick.arm());
        assertEquals("t-1", pick.modelVersion());
        assertEquals(Map.of(0, 0.0, 1, 1.0), pick.support()); // the out of band 3rd is never on the menu
        assertEquals(0, BotGrindTargetLog.choose(entry, bot, ranking, false).index(), "a live target keeps the rule");

        BotCombatManager.cfg.GRIND_TARGET_MODEL_SHARE = 0.0;
        BotGrindTargetLog.Pick rule = BotGrindTargetLog.choose(entry, bot, ranking, true);
        assertEquals(0, rule.index());
        assertEquals("rule", rule.arm());
    }

    @Test
    void theJsonReaderTakesWhatPythonWrites() {
        Object v = BotJson.parse("{\"a\": [1, -2.5e3, true, null, NaN], \"b\": \"x\\\"y\\u0041\"}");
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) v;
        assertEquals("x\"yA", m.get("b"));
        assertEquals(-2500.0, ((List<?>) m.get("a")).get(1));
        assertTrue(Double.isNaN((Double) ((List<?>) m.get("a")).get(4)));
        assertThrows(IllegalArgumentException.class, () -> BotJson.parse("{\"a\": 1,}"));
    }

    private static Character bot(int id, Point pos, int level, int hp, int maxHp) {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(id);
        when(bot.getPosition()).thenReturn(pos);
        when(bot.getLevel()).thenReturn(level);
        when(bot.getHp()).thenReturn(hp);
        when(bot.getCurrentMaxHp()).thenReturn(maxHp);
        return bot;
    }

    private static Monster mob(Point pos, int level, int hp, int maxHp) {
        Monster m = mock(Monster.class);
        when(m.getPosition()).thenReturn(pos);
        when(m.getLevel()).thenReturn(level);
        when(m.getHp()).thenReturn(hp);
        when(m.getMaxHp()).thenReturn(maxHp);
        return m;
    }
}
