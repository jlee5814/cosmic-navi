package server.bots;

import org.junit.jupiter.api.Test;
import server.maps.Foothold;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deity Room (600020100) footholds from the WZ: issue #20, SipsBuddy26 stopped at x -1317, y 156. */
class BotWallFootTest {
    private static Foothold fh(int id, int x1, int y1, int x2, int y2, int prev, int next) {
        Foothold f = new Foothold(new Point(x1, y1), new Point(x2, y2), id);
        f.setPrev(prev);
        f.setNext(next);
        return f;
    }

    // Layer 3: the wall's lowest segment (x -1315, y 120 to 156) and the layer 3 floor it joins.
    private static final Foothold WALL = fh(25, -1315, 120, -1315, 156, 27, 31);
    private static final Foothold LAYER3_FLOOR = fh(31, -1315, 156, -1260, 156, 25, 5);
    // Layer 5: the floor bots walk in on from portal st00, running under the wall's foot.
    private static final Foothold LAYER5_FLOOR = fh(396, -1350, 156, -1260, 156, 394, 395);
    private static final Foothold LAYER5_LEFT = fh(394, -1405, 156, -1350, 156, 392, 396);

    @Test
    void aWallStandingOnAnotherChainsFloorDoesNotStopGroundWalking() {
        assertFalse(BotPhysicsEngine.wallFootJoins(WALL, LAYER5_FLOOR));
        assertFalse(BotPhysicsEngine.wallFootJoins(WALL, LAYER5_LEFT));
    }

    @Test
    void aWallStillStopsTheChainItBelongsTo() {
        assertTrue(BotPhysicsEngine.wallFootJoins(WALL, LAYER3_FLOOR)); // its next link
        Foothold endsAtFoot = fh(900, -1400, 156, -1315, 156, 0, 0); // a floor that ends at the wall's foot
        assertTrue(BotPhysicsEngine.wallFootJoins(WALL, endsAtFoot));
    }
}
