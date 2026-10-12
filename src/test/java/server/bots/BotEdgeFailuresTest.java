package server.bots;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotEdgeFailuresTest {
    private static final int MAP = 910009060;
    private static final int BOT = 4242;
    private final int slack = BotEdgeFailures.cfg.SLACK_MS;
    private final int jitter = BotEdgeFailures.cfg.SLACK_JITTER_MS;

    // Region 1 (start, y 100), region 2 (goal, y 300), region 3 (a ledge above, y -100).
    private static final BotNavigationGraph.Edge DIRECT = new BotNavigationGraph.Edge(1, 2,
            BotNavigationGraph.EdgeType.DROP, new Point(100, 100), new Point(160, 300), 7, 0, 0, 0, 0, 400);
    private static final BotNavigationGraph.Edge UP = new BotNavigationGraph.Edge(1, 3,
            BotNavigationGraph.EdgeType.JUMP, new Point(300, 100), new Point(320, -100), 5, 0, 0, 0, 0, 2000);
    private static final BotNavigationGraph.Edge DOWN = new BotNavigationGraph.Edge(3, 2,
            BotNavigationGraph.EdgeType.DROP, new Point(400, -100), new Point(400, 300), 0, 0, 0, 0, 0, 900);

    @BeforeEach
    void quick() {
        BotEdgeFailures.clearAll();
        BotEdgeFailures.cfg.SLACK_MS = 1000;
        BotEdgeFailures.cfg.SLACK_JITTER_MS = 0;
    }

    @AfterEach
    void restore() {
        BotEdgeFailures.clearAll();
        BotEdgeFailures.cfg.SLACK_MS = slack;
        BotEdgeFailures.cfg.SLACK_JITTER_MS = jitter;
    }

    private static BotEntry entry() {
        return new BotEntry(mock(Character.class), null, null);
    }

    /** Drives {@code entry} at the launch point of DIRECT, staying in region 1, until it gives the edge up. */
    private static long failDirect(BotEntry entry, long now) {
        BotEdgeFailures.track(entry, BOT, MAP, DIRECT, 1, new Point(104, 100), now); // arms: budget 400 + 1000
        for (int i = 0; i < 100; i++) {
            now += 100;
            if (BotEdgeFailures.track(entry, BOT, MAP, DIRECT, 1, new Point(104, 100), now)) {
                return now;
            }
        }
        throw new AssertionError("never gave the edge up");
    }

    @Test
    void givesAnEdgeUpOnlyAfterItsCostPlusSlackAtTheLaunchPoint() {
        BotEntry entry = entry();
        long t0 = 1_000_000;
        assertFalse(BotEdgeFailures.track(entry, BOT, MAP, DIRECT, 1, new Point(900, 100), t0),
                "walking toward a far launch point is not an attempt");
        assertFalse(BotEdgeFailures.track(entry, BOT, MAP, DIRECT, 1, new Point(900, 100), t0 + 60_000));

        long gaveUpAt = failDirect(entry, t0 + 60_000);

        assertEquals(t0 + 60_000 + 1500, gaveUpAt, "first tick past 400 ms of edge cost and 1000 ms of slack");
        assertEquals(BotEdgeFailures.cfg.PENALTY_MS,
                BotEdgeFailures.penaltyMs(BotEdgeFailures.active(BOT, MAP, gaveUpAt), DIRECT));
        assertNull(BotEdgeFailures.active(BOT, MAP + 1, gaveUpAt), "the mark is per map");
        assertNull(BotEdgeFailures.active(BOT + 1, MAP, gaveUpAt), "and per bot");
    }

    @Test
    void timeAwayFromTheEdgeDoesNotCount() {
        BotEntry entry = entry();
        long now = 1_000_000;
        BotEdgeFailures.track(entry, BOT, MAP, DIRECT, 1, new Point(104, 100), now);
        now += 60_000; // a fight or a break between tracked ticks counts as one capped gap: 500 ms
        assertFalse(BotEdgeFailures.track(entry, BOT, MAP, DIRECT, 1, new Point(104, 100), now));
        now += 400;
        assertFalse(BotEdgeFailures.track(entry, BOT, MAP, DIRECT, 1, new Point(104, 100), now));
        now += 400;
        assertFalse(BotEdgeFailures.track(entry, BOT, MAP, DIRECT, 1, new Point(104, 100), now), "1300 of 1400");
        now += 200;
        assertTrue(BotEdgeFailures.track(entry, BOT, MAP, DIRECT, 1, new Point(104, 100), now));
    }

    @Test
    void neverGivesUpMidAirOrAfterArriving() {
        BotEntry entry = entry();
        long now = 1_000_000;
        BotEdgeFailures.track(entry, BOT, MAP, DIRECT, 1, new Point(104, 100), now);
        assertFalse(BotEdgeFailures.track(entry, BOT, MAP, DIRECT, -1, new Point(150, 200), now + 5000),
                "airborne: the landing decides");
        assertFalse(BotEdgeFailures.track(entry, BOT, MAP, DIRECT, 2, new Point(160, 300), now + 9000));
    }

    @Test
    void reachingTheDestinationLaterForgetsTheMark() {
        BotEntry entry = entry();
        long now = failDirect(entry, 1_000_000);
        // Only way left: the bot tries DIRECT again, and this time it lands and moves on to the next edge.
        BotEdgeFailures.track(entry, BOT, MAP, DIRECT, 1, new Point(104, 100), now + 100);
        BotEdgeFailures.track(entry, BOT, MAP, DOWN, 2, new Point(160, 300), now + 900);

        assertNull(BotEdgeFailures.active(BOT, MAP, now + 900));
    }

    @Test
    void theRouteSearchTakesAnotherWayAroundAFailedEdgeAndStillUsesItWhenItIsTheOnlyOne() {
        MapleMap map = new MapleMap(MAP, 0, 0, MAP, 1.0f);
        FootholdTree footholds = new FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        Foothold start = new Foothold(new Point(0, 100), new Point(500, 100), 1);
        Foothold goal = new Foothold(new Point(0, 300), new Point(500, 300), 2);
        Foothold ledge = new Foothold(new Point(300, -100), new Point(500, -100), 3);
        footholds.insert(start);
        footholds.insert(goal);
        footholds.insert(ledge);
        map.setFootholds(footholds);
        BotNavigationGraph.Region r1 = new BotNavigationGraph.Region(1, List.of(new BotNavigationGraph.Segment(start)));
        BotNavigationGraph.Region r2 = new BotNavigationGraph.Region(2, List.of(new BotNavigationGraph.Segment(goal)));
        BotNavigationGraph.Region r3 = new BotNavigationGraph.Region(3, List.of(new BotNavigationGraph.Segment(ledge)));
        BotNavigationGraph withDetour = new BotNavigationGraph(MAP, 1, BotMovementProfile.base(),
                List.of(r1, r2, r3), Map.of(1, r1, 2, r2, 3, r3), Map.of(1, 1, 2, 2, 3, 3),
                Map.of(1, List.of(DIRECT, UP), 3, List.of(DOWN)), Set.of());
        BotNavigationGraph directOnly = new BotNavigationGraph(MAP, 1, BotMovementProfile.base(),
                List.of(r1, r2, r3), Map.of(1, r1, 2, r2, 3, r3), Map.of(1, 1, 2, 2, 3, 3),
                Map.of(1, List.of(DIRECT)), Set.of());
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(BOT);
        Point from = new Point(200, 100);
        Point to = new Point(200, 300);

        assertEquals(List.of(DIRECT), search(withDetour, map, bot, from, to));

        failDirect(entry(), System.currentTimeMillis());

        assertEquals(List.of(UP, DOWN), search(withDetour, map, bot, from, to), "the detour now costs less");
        assertEquals(List.of(DIRECT), search(directOnly, map, bot, from, to), "no other way: keep trying");
        assertNotNull(BotEdgeFailures.active(BOT, MAP, System.currentTimeMillis()));
    }

    private static List<BotNavigationGraph.Edge> search(BotNavigationGraph graph, MapleMap map, Character bot,
                                                        Point from, Point to) {
        return BotNavigationManager.runSearch(graph, map, from, 1, 2, to, "committed", true, false, 0L, false, bot)
                .path();
    }
}
