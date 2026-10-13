package server.bots;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import server.maps.Foothold;
import server.maps.MapleMap;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A foothold drawn right to left is the underside of a structure: the ground probe never stands on it, so a
 * region built from it was a phantom with edges no bot could start. 220000100 fh 25 under a box, Mushroom
 * Shrine 800000000 fh 252 under a roof (39 edges from it landed elsewhere in the sweep).
 */
class BotPhantomRegionTest {
    @ParameterizedTest
    @CsvSource({"220000100,25", "800000000,252"})
    void noRegionIsBuiltFromAFootholdDrawnRightToLeft(int mapId, int footholdId) {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        Foothold fh = map.getFootholds().getAllFootholds().stream()
                .filter(f -> f.getId() == footholdId).findFirst().orElseThrow();
        assertTrue(fh.getX1() > fh.getX2(), "fixture: fh " + footholdId + " is drawn right to left");
        assertFalse(graph.regionIdByFootholdId.containsKey(footholdId), "fh " + footholdId + " makes no region");
        for (Foothold f : map.getFootholds().getAllFootholds()) {
            if (graph.regionIdByFootholdId.containsKey(f.getId())) {
                assertTrue(BotPhysicsEngine.isStandable(f), "map " + mapId + " fh " + f.getId() + " is in a region");
            }
        }
    }

    /** 220000100: the box floor (fh 32) is walled in, and its only plain way out is a jump from x 600, under the
     *  center of the box's ceiling (fh 30, an underside). The ceiling makes no region but still seeds that launch
     *  anchor; without it the window was never sampled and bots that jumped in were trapped. */
    @Test
    void theBoxFloorKeepsItsJumpOutAfterItsCeilingStopsBeingARegion() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(220000100);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        int box = graph.regionIdByFootholdId.get(32);
        int floor = graph.regionIdByFootholdId.get(16);
        assertFalse(graph.regionIdByFootholdId.containsKey(30), "fixture: the ceiling makes no region");
        assertTrue(graph.outgoingByRegionId.getOrDefault(box, java.util.List.of()).stream()
                        .anyMatch(e -> e.type == BotNavigationGraph.EdgeType.JUMP && e.toRegionId == floor),
                "a plain jump leaves the box for the floor below");
    }
}
