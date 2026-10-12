package server.bots;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A bot's memory of moves that didn't work for it. The builder authors edges from simulated physics, the
 * sweep (tools/navgate) catches the ones that fail there, and a few still fail live: a small wall the
 * runway check missed (New Leaf City x 1715 before graph v72), a ledge a knockback keeps undoing. Without
 * this a bot replans the same cheapest route and retries that edge for hours.
 *
 * <p>Once the bot reaches an edge's launch point, the edge gets its own travel time plus a jittered slack to
 * finish. A bot still in the edge's source region past that gives the edge up, the way a player who keeps
 * missing a jump tries another way: the edge costs it {@code PENALTY_MS} more in its own route search for a
 * jittered while, so the planner takes another route when one exists and still uses this one when none
 * does. Reaching the edge's destination later clears the mark. Every give up is logged; a repeated one is a
 * builder bug to fix at the source.
 */
final class BotEdgeFailures {
    private static final Logger log = LoggerFactory.getLogger(BotEdgeFailures.class);

    static final class Config {
        // Off (-Dbots.edge-give-up=off): bots retry an edge however long it takes. BotEdgeSweepCli turns it
        // off for forced edges, which are the thing under test. A property, so a sweep tool built on a newer
        // branch still runs against a base without this class.
        boolean ENABLED = !"off".equals(System.getProperty("bots.edge-give-up"));
        int ARM_PX = 60;                 // within this of the launch window (x and y) the attempt clock runs
        int SLACK_MS = 5000;             // on top of the edge's own cost
        int SLACK_JITTER_MS = 3000;
        int PENALTY_MS = 60_000;
        long REMEMBER_MS = 600_000;
        long REMEMBER_JITTER_MS = 300_000;
    }

    static final Config cfg = new Config();

    /** An edge by its geometry, so a mark survives a graph rebuild (region ids are per graph). */
    record Key(BotNavigationGraph.EdgeType type, int startX, int startY, int endX, int endY) {
        static Key of(BotNavigationGraph.Edge e) {
            return new Key(e.type, e.startPoint.x, e.startPoint.y, e.endPoint.x, e.endPoint.y);
        }
    }

    /** The edge a bot is attempting: whether its clock runs, the attempt time it has spent, its budget. */
    static final class Attempt {
        final BotNavigationGraph.Edge edge;
        final Key key;
        final int mapId;
        boolean armed;
        long spentMs;
        long budgetMs;

        Attempt(BotNavigationGraph.Edge edge, int mapId) {
            this.edge = edge;
            this.key = Key.of(edge);
            this.mapId = mapId;
        }
    }

    // bot id -> map id -> failed edge -> remembered until (epoch ms)
    private static final Map<Integer, Map<Integer, Map<Key, Long>>> FAILED = new ConcurrentHashMap<>();

    private BotEdgeFailures() {
    }

    /**
     * Called on each AI tick with the edge the bot is following. Returns true when the bot just gave the
     * edge up; the caller drops the edge and the committed route so the next tick replans around it.
     * Attempt time is game time: one AI tick per call, the clock physics and edge costs run on, so a
     * server under load or the sim lab running faster than real time judges an edge the same way, and
     * ticks spent elsewhere (a fight, a break) never count. {@code now} (wall time) only dates the mark.
     */
    static boolean track(BotEntry entry, int botId, int mapId, BotNavigationGraph.Edge edge, int regionId,
                         Point botPos, long now) {
        if (!cfg.ENABLED) {
            return false;
        }
        Attempt a = entry.edgeAttempt;
        if (a == null || a.mapId != mapId || !a.key.equals(Key.of(edge))) {
            if (a != null && a.mapId == mapId && regionId == a.edge.toRegionId) {
                forget(botId, mapId, a.key); // the previous edge got the bot where it was going
            }
            a = new Attempt(edge, mapId);
            entry.edgeAttempt = a;
        }
        if (regionId == edge.toRegionId && edge.fromRegionId != edge.toRegionId) {
            return false; // arrived; the planner retires the edge
        }
        if (!a.armed) {
            if (regionId != edge.fromRegionId
                    || !edge.containsLaunchX(botPos.x, cfg.ARM_PX) || !edge.containsLaunchY(botPos.y, cfg.ARM_PX)) {
                return false; // still on the way to the launch point
            }
            a.armed = true;
            a.budgetMs = Math.max(0, edge.cost) + cfg.SLACK_MS
                    + ThreadLocalRandom.current().nextInt(cfg.SLACK_JITTER_MS + 1);
            return false;
        }
        a.spentMs += BotManager.cfg.AI_TICK_MS;
        if (a.spentMs <= a.budgetMs || regionId != edge.fromRegionId) {
            return false; // in time, or mid air / on a rope / somewhere the next plan will sort out
        }
        long until = now + cfg.REMEMBER_MS + ThreadLocalRandom.current().nextLong(cfg.REMEMBER_JITTER_MS + 1);
        FAILED.computeIfAbsent(botId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(mapId, k -> new ConcurrentHashMap<>())
                .put(a.key, until);
        entry.edgeAttempt = null;
        log.info("Bot {} gave up a {} edge on map {} from ({},{}) to ({},{}) after {} ms (budget {} ms); "
                        + "it avoids the edge for {} s",
                botId, edge.type, mapId, edge.startPoint.x, edge.startPoint.y, edge.endPoint.x, edge.endPoint.y,
                a.spentMs, a.budgetMs, (until - now) / 1000);
        return true;
    }

    /** The bot's failed edges on this map still remembered, or null when there are none. */
    static Map<Key, Long> active(int botId, int mapId, long now) {
        Map<Integer, Map<Key, Long>> byMap = FAILED.get(botId);
        Map<Key, Long> failed = byMap == null ? null : byMap.get(mapId);
        if (failed == null) {
            return null;
        }
        failed.values().removeIf(until -> until <= now);
        return failed.isEmpty() ? null : failed;
    }

    /** Extra route cost of {@code edge} for a bot with these failed edges. */
    static int penaltyMs(Map<Key, Long> failed, BotNavigationGraph.Edge edge) {
        return failed != null && failed.containsKey(Key.of(edge)) ? cfg.PENALTY_MS : 0;
    }

    private static void forget(int botId, int mapId, Key key) {
        Map<Integer, Map<Key, Long>> byMap = FAILED.get(botId);
        Map<Key, Long> failed = byMap == null ? null : byMap.get(mapId);
        if (failed != null) {
            failed.remove(key);
        }
    }

    static void clearAll() {
        FAILED.clear();
    }
}
