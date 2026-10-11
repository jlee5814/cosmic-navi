package server.bots;

import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #30. The graph builder authored walk off drops into walls that rise from a platform's far end, and
 * bots froze pressed against them: SipsBuddy26 at x 85 on map 800030000 (Forest of Animals) and again at
 * x 4457 on map 800000000 (Mushroom Shrine). The builder's runway check read walls against the launch
 * foothold only, so a wall joined to the end segment of the same chain passed it. Footholds below are
 * copied from the WZ files.
 */
class BotWalkOffIntoWallTest {

    private static final int[][] FOREST_OF_ANIMALS = {
            // {id, x1, y1, x2, y2, prev, next}
            {54, -45, -235, 6, -235, 0, 76},       // R14, the platform above the step
            {76, 6, -235, 45, -235, 54, 77},
            {77, 45, -235, 84, -235, 76, 78},
            {78, 84, -235, 84, -210, 77, 79},      // wall rising from R17's left end
            {79, 84, -210, 84, -175, 78, 80},
            {80, 84, -175, 96, -175, 79, 72},      // R17, where SipsBuddy26 froze
            {72, 96, -175, 174, -175, 80, 73},
            {73, 174, -175, 174, -150, 72, 74},    // wall falling from R17's right end: a real ledge
            {74, 174, -150, 174, -115, 73, 75},
            {75, 174, -115, 186, -115, 74, 68},    // R20
            {68, 186, -115, 264, -115, 75, 0},
            {228, -90, 35, 0, 35, 0, 238},         // R26, the bottom floor
            {238, 0, 35, 90, 35, 228, 231},
            {231, 90, 35, 180, 35, 238, 244},
            {244, 180, 35, 270, 35, 231, 0},
    };

    private static final int[][] MUSHROOM_SHRINE = {
            {29, 4371, 120, 4371, 60, 0, 30},      // wall falling from the left end: a real ledge
            {30, 4371, 60, 4371, 35, 29, 31},
            {31, 4371, 35, 4449, 35, 30, 32},      // the platform SipsBuddy26 froze on
            {32, 4449, 35, 4461, 35, 31, 33},
            {33, 4461, 35, 4461, 0, 32, 26},       // wall rising from the right end
            {26, 4461, 0, 4461, -25, 33, 27},
            {27, 4461, -25, 4500, -25, 26, 0},
            {219, 4300, 95, 4410, 95, 0, 214},     // the floor below
            {214, 4410, 95, 4500, 95, 219, 213},
            {213, 4500, 95, 4590, 95, 214, 0},
    };

    private static Map<Integer, Foothold> footholds(int[][] rows) {
        Map<Integer, Foothold> byId = new HashMap<>();
        for (int[] f : rows) {
            Foothold fh = new Foothold(new Point(f[1], f[2]), new Point(f[3], f[4]), f[0]);
            fh.setPrev(f[5]);
            fh.setNext(f[6]);
            byId.put(f[0], fh);
        }
        return byId;
    }

    private static MapleMap buildMap(int mapId, int[][] rows) {
        MapleMap map = new MapleMap(mapId, 0, 0, mapId, 1.0f);
        FootholdTree tree = new FootholdTree(new Point(-30000, -30000), new Point(30000, 30000));
        footholds(rows).values().forEach(tree::insert);
        map.setFootholds(tree);
        return map;
    }

    private static boolean hasWalkOff(BotNavigationGraph graph, int fromFoothold, int direction) {
        int from = graph.regionIdByFootholdId.get(fromFoothold);
        return graph.getOutgoing(from).stream().anyMatch(e -> e.type == BotNavigationGraph.EdgeType.DROP
                && Integer.signum(e.launchStepX) == direction);
    }

    @Test
    void aWallRisingFromTheChainsFarEndJoinsTheChain() {
        Map<Integer, Foothold> byId = footholds(FOREST_OF_ANIMALS);
        // The old check: the wall joins only the end segment (fh80), not fh72 where a runway starts.
        assertFalse(BotPhysicsEngine.wallFootJoins(byId.get(79), byId.get(72)));
        assertTrue(BotPhysicsEngine.wallJoinsChain(byId.get(79), byId.get(72), byId));

        Map<Integer, Foothold> shrine = footholds(MUSHROOM_SHRINE);
        assertTrue(BotPhysicsEngine.wallJoinsChain(shrine.get(33), shrine.get(31), shrine));
    }

    @Test
    void aWallOnAnotherChainsFloorStillPasses() {
        // Deity Room (600020100), issue #20: the layer 3 wall's foot sits on the layer 5 floor chain.
        Map<Integer, Foothold> byId = footholds(new int[][]{
                {25, -1315, 120, -1315, 156, 27, 31},
                {31, -1315, 156, -1260, 156, 25, 5},
                {394, -1405, 156, -1350, 156, 0, 396},
                {396, -1350, 156, -1260, 156, 394, 0},
        });
        assertFalse(BotPhysicsEngine.wallJoinsChain(byId.get(25), byId.get(396), byId));
        assertFalse(BotPhysicsEngine.wallJoinsChain(byId.get(25), byId.get(394), byId));
    }

    @Test
    void noWalkOffDropIntoTheWallOnForestOfAnimals() {
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(buildMap(800030000, FOREST_OF_ANIMALS));
        assertFalse(hasWalkOff(graph, 72, -1), "R17's left end rises into a wall; there is no ledge to walk off");
        assertTrue(hasWalkOff(graph, 72, 1), "R17's right end is a real ledge down to R20");
    }

    @Test
    void noWalkOffDropIntoTheWallOnMushroomShrine() {
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(buildMap(800000000, MUSHROOM_SHRINE));
        assertFalse(hasWalkOff(graph, 31, 1), "the right end rises into a wall; there is no ledge to walk off");
        assertTrue(hasWalkOff(graph, 31, -1), "the left end is a real ledge down to the floor below");
    }
}
