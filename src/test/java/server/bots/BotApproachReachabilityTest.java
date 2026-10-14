package server.bots;

import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * NPC approach points ({@link BotTravelManager#pickReachableApproachPoint}) keep candidates the bot can reach
 * on foot. They used to run a walk-only A* per candidate (a 441 ms tick in Ellinia); they now ask
 * {@link BotNavigationGraph#canReach}. Both must give the same answer for every region.
 */
class BotApproachReachabilityTest {
    @Test
    void cachedReachabilityAgreesWithAWalkOnlySearchOnEllinia() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
        MapleMap ellinia = BotNavigationMapLoader.loadMapGeometry(101000000);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(ellinia);
        int checked = 0;
        for (BotNavigationGraph.Region start : graph.regions) {
            if (start.isRopeRegion || start.id % 5 != 0) {
                continue; // every fifth ground region as a start keeps the test quick
            }
            for (BotNavigationGraph.Region target : graph.regions) {
                if (target.id == start.id) {
                    continue;
                }
                boolean searched = !BotNavigationManager.runSearch(graph, ellinia, start.centerPoint(), start.id,
                        target.id, target.centerPoint(), "test", false, false, 0L).path().isEmpty();
                assertEquals(searched, graph.canReach(start.id, target.id, 0), start.id + " -> " + target.id);
                checked++;
            }
        }
        assertEquals(true, checked > 100, "checked " + checked + " pairs");
    }
}
