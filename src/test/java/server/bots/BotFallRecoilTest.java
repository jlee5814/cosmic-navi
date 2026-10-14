package server.bots;

import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Graph v78: a landing that deals fall damage knocks the bot back ({@link BotCombatManager#applyFallDamage}), so it
 * comes to rest where the recoil throws it ({@link BotPhysicsEngine#settleLanding}). 120 of 133 swept landings
 * elsewhere on main were falls long enough to hurt.
 */
class BotFallRecoilTest {

    private static MapleMap load(int mapId) {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
        return BotNavigationMapLoader.loadMapGeometry(mapId);
    }

    /** Ellinia tree 101020000: a 1350 px straight drop from (-172,-3819) lands on the ledge at (-172,-2467), and the
     *  sweep's bot was thrown right onto the rope at x -98. The drop is authored only where the bot rests facing
     *  either way, so that ledge is no target from x -172. */
    @Test
    void aLongStraightDropWhoseRecoilGrabsARopeIsNotAuthoredToTheLedge() {
        MapleMap map = load(101020000);
        Point launch = new Point(-172, -3819);
        BotPhysicsEngine.JumpLanding landing = BotPhysicsEngine.simulateDownJumpLanding(map, launch);
        assertNotNull(landing);
        assertTrue(BotCombatManager.fallDamageFromDistance(landing.fallPx()) > 0, "fall " + landing.fallPx());
        BotPhysicsEngine.PostLandingJump right = BotPhysicsEngine.settleLanding(map, landing, -1, 0,
                BotMovementProfile.base(), 3);
        assertTrue(right.lostGround(), "facing left, the recoil throws the bot right onto the rope at x -98");

        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        int from = graph.findRegionId(map, launch);
        int ledge = graph.findRegionId(map, new Point(-172, -2467));
        for (BotNavigationGraph.Edge e : graph.getOutgoing(from)) {
            assertFalse(e.type == BotNavigationGraph.EdgeType.DROP && e.launchStepX == 0 && e.toRegionId == ledge
                            && e.containsLaunchX(-172),
                    "straight drop r" + from + "->r" + ledge + " window " + e.launchMinX + ".." + e.launchMaxX);
        }
    }

    /** A short drop deals no fall damage and settles where it lands, as before. */
    @Test
    void aShortLandingIsUnchanged() {
        MapleMap map = load(100000000);
        BotPhysicsEngine.JumpLanding landing = BotPhysicsEngine.simulateFallLanding(map, new Point(290, 88), 6);
        assertNotNull(landing);
        assertTrue(BotCombatManager.fallDamageFromDistance(landing.fallPx()) <= 0);
        BotPhysicsEngine.PostLandingJump plain = BotPhysicsEngine.simulatePostLandingGroundTicks(map, landing, 0,
                BotMovementProfile.base(), 3);
        BotPhysicsEngine.PostLandingJump settled = BotPhysicsEngine.settleLanding(map, landing, 1, 0,
                BotMovementProfile.base(), 3);
        assertTrue(plain.finalPoint().equals(settled.finalPoint()), plain.finalPoint() + " vs " + settled.finalPoint());
    }
}
