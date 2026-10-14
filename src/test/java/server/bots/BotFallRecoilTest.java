package server.bots;

import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Path;

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

    /** Ellinia tree 101020000: a 1582 px straight drop from (-135,-3828) lands at (-135,-2247), beside the rope at
     *  x -98. Facing left, the recoil throws the bot right onto the rope at -98; facing right, it rests on
     *  the ground to the left. The drop is authored, and the executor turns right before dropping. From x -172 the
     *  ledge at y -2467 sits between the ropes at -252 and -98, and the recoil grabs one either way: no drop. */
    @Test
    void aLongStraightDropTurnsTheBotSoTheRecoilRestsInTheTarget() {
        MapleMap map = load(101020000);
        Point launch = new Point(-135, -3828);
        BotPhysicsEngine.JumpLanding landing = BotPhysicsEngine.simulateDownJumpLanding(map, launch);
        assertNotNull(landing);
        assertTrue(BotCombatManager.fallDamageFromDistance(landing.fallPx()) > 0, "fall " + landing.fallPx());
        BotPhysicsEngine.PostLandingJump facingLeft = BotPhysicsEngine.settleLanding(map, landing, -1, 0,
                BotMovementProfile.base(), 3);
        assertTrue(facingLeft.lostGround(), "facing left, the recoil throws the bot right onto the rope at x -98");

        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        int from = graph.findRegionId(map, launch);
        boolean checked = false;
        for (BotNavigationGraph.Edge e : graph.getOutgoing(from)) {
            if (e.type != BotNavigationGraph.EdgeType.DROP || e.launchStepX != 0) {
                continue;
            }
            assertTrue(!e.containsLaunchX(-172), "no drop from x -172: " + e.startPoint + " r" + e.toRegionId);
            if (!e.containsLaunchX(launch.x)) {
                continue;
            }
            int facing = BotNavigationGraphProvider.straightDropFacing(map, launch, e.toRegionId,
                    graph.regionIdByFootholdId, BotMovementProfile.base(), -1);
            assertTrue(facing == 1, "drop r" + from + "->r" + e.toRegionId + ": the bot must turn right, got " + facing);
            checked = true;
        }
        assertTrue(checked, "a straight drop from x -135");
    }

    /** The same tree, run live: a straight drop from (-135,-3828) lands on a slope by the rope at x -98. The
     *  executor turns right before dropping, and the slope must not turn the bot back on landing (it did:
     *  {@code landOnGround} took facing from the slide), or the recoil throws it onto the rope. */
    @Test
    void aDamagingStraightDropRunLiveRestsInItsTarget() {
        server.TimerManager.getInstance().start();
        MapleMap map = load(101020000);
        Point launch = new Point(-135, -3828);
        BotNavigationGraph graph = BotNavigationGraphProvider.getGraph(map);
        int from = graph.findRegionId(map, launch);
        BotNavigationGraph.Edge drop = null;
        for (BotNavigationGraph.Edge e : graph.getOutgoing(from)) {
            if (e.type == BotNavigationGraph.EdgeType.DROP && e.launchStepX == 0 && e.containsLaunchX(launch.x)) {
                drop = e;
            }
        }
        assertNotNull(drop, "a straight drop from x -135");
        BotMovementSimulationLab lab = BotMovementSimulationLab.fromMap(map);
        BotEntry entry = lab.spawnBot("recoil", 1, map, new Point(launch));
        lab.teleport("recoil", new Point(launch));
        lab.primeMapState("recoil");
        entry.facingDir = -1; // walked in from the right, facing the rope side
        lab.setNavState("recoil", drop, drop.toRegionId, false);
        lab.setMoveTarget("recoil", new Point(drop.endPoint), true);
        boolean hurt = false;
        for (int t = 0; t < 120; t++) {
            lab.step(1);
            hurt |= entry.mobHitCooldownMs > 0; // applyFallDamage started the hit cooldown
        }
        Point end = entry.bot.getPosition();
        lab.remove("recoil");
        assertTrue(hurt, "the fall hurts");
        assertTrue(!entry.climbing && BotNavigationManager.resolveCurrentRegionId(graph, entry, map, end) == drop.toRegionId,
                "rests in r" + drop.toRegionId + ", got " + end + (entry.climbing ? " on a rope" : ""));
    }

    /** Eos Tower 221023400: a jump right from (-69,-1815) lands at (147,-926) and the recoil throws the bot off that
     *  ledge, further down the tower. The edge must end where it rests, in its target: an end point on the ledge
     *  sent the router to the ledge's region instead (in the sweep, through a portal). */
    @Test
    void aJumpThatHurtsEndsInItsTarget() {
        MapleMap map = load(221023400);
        Point launch = new Point(-69, -1815);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        int from = graph.findRegionId(map, launch);
        boolean checked = false;
        for (BotNavigationGraph.Edge e : graph.getOutgoing(from)) {
            if (e.type != BotNavigationGraph.EdgeType.JUMP || e.launchStepX <= 0 || !e.containsLaunchX(launch.x)) {
                continue;
            }
            BotPhysicsEngine.JumpLanding touch = BotPhysicsEngine.simulateJumpLanding(map, e.startPoint, e.launchStepX);
            if (touch == null || BotCombatManager.fallDamageFromDistance(touch.fallPx()) <= 0) {
                continue;
            }
            assertTrue(graph.findRegionId(map, e.endPoint) == e.toRegionId,
                    "jump r" + from + "->r" + e.toRegionId + " ends at " + e.endPoint + " in r" + graph.findRegionId(map, e.endPoint));
            checked = true;
        }
        assertTrue(checked, "a jump right from x -69 that hurts");
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
