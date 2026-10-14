package server.bots;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import server.maps.MapleMap;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The edge sweep (BotEdgeSweepCli) found 35 walk off drops on 18 maps at a platform that ends at the map's
 * side edge. Live walking stops at the edge, so the bot stands there and never falls. SipsBuddy2 sat on
 * Sky Terrace 5 for 380 minutes on 2026-10-10. Loads the real maps from the WZ files.
 */
class BotWalkOffMapEdgeTest {

    @ParameterizedTest
    @ValueSource(ints = {220000002, 220011000, 220040300}) // Ludibrium Pharmacy, Sky Terrace 5, Path of Time 3
    void noWalkOffDropPastTheMapsSideEdge(int mapId) {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        int leftEdge = map.getFootholds().getMinDropX();
        int rightEdge = map.getFootholds().getMaxDropX();
        boolean anyWalkOff = false;
        for (BotNavigationGraph.Region region : graph.regions) {
            for (BotNavigationGraph.Edge e : graph.getOutgoing(region.id)) {
                if (e.type != BotNavigationGraph.EdgeType.DROP || e.launchStepX == 0 || region.isRopeRegion) {
                    continue;
                }
                anyWalkOff = true;
                int ledgeX = e.launchStepX < 0 ? region.minX : region.maxX;
                boolean atEdge = e.launchStepX < 0 ? ledgeX <= leftEdge : ledgeX >= rightEdge;
                assertFalse(atEdge, "map " + mapId + ": walk off DROP r" + region.id + "->r" + e.toRegionId
                        + " leaves at the map's side edge x " + ledgeX);
            }
        }
        assertTrue(anyWalkOff, "ledges inside the map still get their walk off drops");
    }

    /** Every walk off drop must leave the ground on its first step past the ledge. New Leaf City had two
     *  that walked up a 6 and a 9 px wall onto the rest of the chain instead (x 1715 and x 4077). */
    @ParameterizedTest
    @ValueSource(ints = {600000000, 220000002, 220011000, 220040300})
    void everyWalkOffDropLeavesTheGroundAtItsLedge(int mapId) {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        for (BotNavigationGraph.Region region : graph.regions) {
            for (BotNavigationGraph.Edge e : graph.getOutgoing(region.id)) {
                if (e.type != BotNavigationGraph.EdgeType.DROP || e.launchStepX == 0 || region.isRopeRegion) {
                    continue;
                }
                java.awt.Point ledge = e.launchStepX < 0 ? region.leftPoint() : region.rightPoint();
                assertFalse(BotPhysicsEngine.canWalkGroundStep(map, ledge, e.launchStepX),
                        "map " + mapId + ": walk off DROP r" + region.id + "->r" + e.toRegionId
                                + " keeps walking on the ground past its ledge at " + ledge);
            }
        }
    }

    /** New Leaf City's valley at x 1715 to 1748: its left end is the foot of a 6 px wall up to the chain
     *  on the left. Walking left, the bot stops a step short of the wall and never falls, but a first step
     *  tested from the end itself passed the wall, so a left walk off drop was authored and the bot stood
     *  in the corner. The right end is a real ledge and keeps its drop. */
    @org.junit.jupiter.api.Test
    void noWalkOffDropIntoAWallRisingFromTheRegionEnd() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(600000000);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        int valley = graph.findRegionId(map, new java.awt.Point(1731, 355));
        boolean left = false;
        boolean right = false;
        for (BotNavigationGraph.Edge e : graph.getOutgoing(valley)) {
            if (e.type == BotNavigationGraph.EdgeType.DROP && e.launchStepX != 0) {
                left |= e.launchStepX < 0;
                right |= e.launchStepX > 0;
            }
        }
        assertFalse(left, "no walk off drop to the left of region " + valley);
        assertTrue(right, "the ledge at x 1748 keeps its walk off drop");
    }

    /** Henesys stairs: walking right off the step at (290,88), a bot lands on the 24 px stair at (349,113) when it
     *  leaves at 6 px a tick and mostly clears it to (364,137) at 7, so where it lands is a coin flip of the walk's
     *  carry phase. The sweep's live bot cleared it while the old edge targeted the near stair. No drop is authored
     *  where the phases disagree; the pet walking road's walk off, where nearly all of them agree, stays
     *  ({@code BotMovementSimulationLabTest.shouldUsePetWalkingRoadLocalWalkOffDrop}). */
    @org.junit.jupiter.api.Test
    void aStairWalkOffWhoseLandingIsACoinFlipOfTheWalkPhaseIsNotAuthored() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
        MapleMap henesys = BotNavigationMapLoader.loadMapGeometry(100000000);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(henesys);
        int from = graph.findRegionId(henesys, new java.awt.Point(290, 88));
        for (BotNavigationGraph.Edge e : graph.getOutgoing(from)) {
            assertFalse(e.type == BotNavigationGraph.EdgeType.DROP && e.launchStepX > 0,
                    "walk off drop r" + from + "->r" + e.toRegionId);
        }
    }
}
