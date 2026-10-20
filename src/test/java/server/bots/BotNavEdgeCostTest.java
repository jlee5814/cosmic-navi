package server.bots;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import testutil.RequiresWz;

import java.awt.Point;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ludibrium 220040100 at speed 105 / jump 100: a walk off DROP r14 to r16 cost -2147483329 (a second fall simulated
 * from the nominal step missed, and its Integer.MAX_VALUE sentinel plus the runway wrapped). A* relaxed around it
 * forever and reconstructPath looped on two bot tick threads, two cores at 100% (SipsBuddy7, SipsBuddy46).
 */
@RequiresWz
class BotNavEdgeCostTest {
    private static final int LUDIBRIUM = 220040100;
    private static final BotMovementProfile PROFILE = new BotMovementProfile(105, 100, false);

    @BeforeAll
    static void initWzPath() {
        System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
    }

    @Test
    void everyEdgeHasAKnownCostAndEverySearchFromTheStuckSpotEnds() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(LUDIBRIUM);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map, PROFILE);
        List<String> bad = new ArrayList<>();
        for (List<BotNavigationGraph.Edge> edges : graph.outgoingByRegionId.values()) {
            for (BotNavigationGraph.Edge e : edges) {
                if (e.cost < 0 || e.cost == Integer.MAX_VALUE) {
                    bad.add(e.type + " r" + e.fromRegionId + " to r" + e.toRegionId + " cost " + e.cost);
                }
            }
        }
        assertTrue(bad.isEmpty(), "edges with unknown or negative costs: " + bad);

        Point stuck = new Point(-675, 252); // where SipsBuddy7 stood
        int from = graph.findRegionId(map, stuck);
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            for (BotNavigationGraph.Region r : graph.regions) {
                BotNavigationManager.findPathForTargetScore(graph, map, stuck, from, r.id, r.centerPoint());
            }
        });
    }
}
