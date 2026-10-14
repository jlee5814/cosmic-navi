package server.bots;

import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phantom edge sweep. For every ground edge the builder authored on a map, put a bot at the edge's start,
 * hand it the edge, and run the live movement pipeline (the same per tick physics a bot runs in game). An
 * edge the bot can't finish within the budget is a phantom: A* will route through it and a real bot will
 * stall there (issue #30 was one). Writes one TSV line per tested edge and prints a summary per map.
 *
 * <p>Usage: BotEdgeSweepCli out.tsv mapId [mapId ...]   (or @file with one map id per line)
 *
 * <p>Passing moves are written as {@code ok}, so a gate can also see a working move that a change removed. {@code --probe file} tests trips instead of edges: lines of map, startX, startY, endX, endY
 * (tab separated); the bot gets only the target and the live router picks the way, and each row reads
 * {@code fails:k/N}. That answers whether a bot can still get from A to B at all, whatever edges a branch has.
 */
public final class BotEdgeSweepCli {
    private static final Set<BotNavigationGraph.EdgeType> TESTED = EnumSet.of(
            BotNavigationGraph.EdgeType.WALK, BotNavigationGraph.EdgeType.JUMP, BotNavigationGraph.EdgeType.DROP);
    private static final int BUDGET_MS = 8000;
    /** --route: don't hand the bot the edge; give it only the target and let the live router choose, with a
     *  longer budget. An edge that fails when forced but passes here is a phantom A* routes around; one that
     *  fails both ways is a trap a real bot can walk into. */
    private static boolean routeMode = false;
    private static int budgetMs = BUDGET_MS;
    /** Landing within this many px of the edge's end counts as done, whatever region overlapping ground
     *  resolves to; edges shorter than this are skipped. */
    private static final int MIN_EDGE_PX = 15;

    private BotEdgeSweepCli() {
    }

    public static void main(String[] args) throws Exception {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
        server.TimerManager.getInstance().start(); // the movement pipeline schedules follow up work
        Path out = Path.of(args[0]);
        List<Integer> mapIds = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--attempts")) {
                attempts = Integer.parseInt(args[++i]);
                continue;
            }
            if (args[i].equals("--only")) {
                only = new java.util.HashSet<>(Files.readAllLines(Path.of(args[++i])));
                continue;
            }
            if (args[i].equals("--probe")) {
                for (String line : Files.readAllLines(Path.of(args[++i]))) {
                    String[] f = line.split("\t");
                    if (f.length >= 5) {
                        probes.computeIfAbsent(Integer.parseInt(f[0]), k -> new ArrayList<>()).add(new Point[]{
                                new Point(Integer.parseInt(f[1]), Integer.parseInt(f[2])),
                                new Point(Integer.parseInt(f[3]), Integer.parseInt(f[4]))});
                    }
                }
                routeMode = true;
                budgetMs = 20000;
                continue;
            }
            if (args[i].equals("--route")) {
                routeMode = true;
                budgetMs = 20000;
                continue;
            }
            if (args[i].startsWith("@")) {
                for (String line : Files.readAllLines(Path.of(args[i].substring(1)))) {
                    if (!line.isBlank()) {
                        mapIds.add(Integer.parseInt(line.trim()));
                    }
                }
            } else {
                mapIds.add(Integer.parseInt(args[i]));
            }
        }
        // A forced edge is the thing under test: the bot must not give it up and route around it. Route and
        // probe runs keep giving up on, since that is how a live bot finds another way.
        System.setProperty("bots.edge-give-up", routeMode ? "on" : "off"); // read by BotEdgeFailures, if present
        List<String> rows = new ArrayList<>();
        rows.add("map\tfrom\tto\ttype\tstepX\tstartX\tstartY\tendX\tendY\tendRegion\tfinalX\tfinalY\tmovedPx\tverdict");
        int totalTested = 0;
        int totalFailed = 0;
        if (!probes.isEmpty()) {
            mapIds = new ArrayList<>(probes.keySet());
        }
        for (int mapId : mapIds) {
            MapResult r;
            try {
                r = probes.isEmpty() ? sweepMap(mapId, rows) : probeMap(mapId, probes.get(mapId), rows);
            } catch (Throwable t) {
                System.out.println(mapId + "\tERROR\t" + t);
                continue;
            }
            totalTested += r.tested;
            totalFailed += r.failed;
            System.out.println(mapId + "\ttested=" + r.tested + "\tfailed=" + r.failed + "\tbyType=" + r.failedByType);
            Files.write(out, rows);
        }
        System.out.println("TOTAL\ttested=" + totalTested + "\tfailed=" + totalFailed);
        System.exit(0); // the timer threads would keep the JVM alive
    }

    private static int attempts = 3;
    /** --only file: confirm mode. Tests only the listed moves (map, type, stepX, startX, startY, endX, endY,
     *  tab separated), runs every attempt, and writes fails:k/N for each, so a gate can tell a real
     *  regression from a move that fails only some of the time. */
    private static java.util.Set<String> only = null;
    /** --probe: map id to its trips, each {start, end}. */
    private static final Map<Integer, List<Point[]>> probes = new java.util.TreeMap<>();

    private record Attempt(boolean reached, int maxMoved, Point end, int endRegion, int fromRegion, String error) {
        String verdict(BotNavigationGraph.Edge edge) {
            return error != null ? "error:" + error
                    : maxMoved < 12 ? "frozen" : endRegion == fromRegion ? "stayed" : "wrong-region";
        }
    }

    private static Attempt attempt(BotMovementSimulationLab lab, MapleMap map, BotNavigationGraph graph,
                                   BotNavigationGraph.Edge edge, int botId) {
        return attempt(lab, map, graph, edge.startPoint, edge.endPoint, edge.fromRegionId, edge.toRegionId,
                routeMode ? null : edge, botId);
    }

    /** One try from {@code startPoint} to {@code endPoint}: handed {@code forced} as its edge, or, when null,
     *  only the target, so the live router chooses. Done when grounded in {@code toRegion} or near the end. */
    private static Attempt attempt(BotMovementSimulationLab lab, MapleMap map, BotNavigationGraph graph,
                                   Point startPoint, Point endPoint, int fromRegion, int toRegion,
                                   BotNavigationGraph.Edge forced, int botId) {
        String name = "sweep" + botId;
        BotEntry entry = lab.spawnBot(name, botId, map, new Point(startPoint));
        lab.teleport(name, new Point(startPoint));
        lab.primeMapState(name);
        if (forced != null) {
            lab.setNavState(name, forced, forced.toRegionId, false);
        }
        lab.setMoveTarget(name, new Point(endPoint), true);
        Point start = new Point(entry.bot.getPosition());
        int maxMoved = 0;
        boolean reached = false;
        // A forced edge gets its own flight time on top: a fall through a tall shaft outlasts the plain budget
        // (101020000 x 864 falls 5586 px), and the bot is still in the air when time runs out.
        int ticks = (forced == null ? budgetMs : Math.max(budgetMs, forced.cost + BUDGET_MS)) / BotMovementManager.cfg.TICK_MS;
        String error = null;
        try {
            for (int t = 0; t < ticks && !reached; t++) {
                lab.step(1);
                Point p = entry.bot.getPosition();
                maxMoved = Math.max(maxMoved, (int) start.distance(p));
                if (!entry.inAir && !entry.climbing
                        && (p.distance(endPoint) <= MIN_EDGE_PX
                        || BotNavigationManager.resolveCurrentRegionId(graph, entry, map, p) == toRegion)) {
                    reached = true;
                }
            }
        } catch (RuntimeException e) {
            error = e.getClass().getSimpleName();
        }
        lab.remove(name);
        Point p = new Point(entry.bot.getPosition());
        int endRegion = BotNavigationManager.resolveCurrentRegionId(graph, entry, map, p);
        return new Attempt(reached, maxMoved, p, endRegion, fromRegion, error);
    }

    /** --probe: each trip on this map, every attempt run, written as fails:k/N. */
    private static MapResult probeMap(int mapId, List<Point[]> trips, List<String> rows) {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
        BotMovementSimulationLab lab = BotMovementSimulationLab.fromMap(map);
        BotNavigationGraph graph = BotNavigationGraphProvider.getGraph(map);
        int failed = 0;
        int botId = 1;
        for (Point[] trip : trips) {
            int from = graph.findRegionId(map, trip[0]);
            int to = graph.findRegionId(map, trip[1]);
            int failures = 0;
            Attempt first = null;
            for (int a = 0; a < attempts; a++) {
                Attempt at = attempt(lab, map, graph, trip[0], trip[1], from, to, null, botId++);
                first = first == null ? at : first;
                if (!at.reached()) {
                    failures++;
                }
            }
            if (failures == attempts) {
                failed++;
            }
            rows.add(row(mapId, from, to, "TRIP", 0, trip[0], trip[1], first, "fails:" + failures + "/" + attempts));
        }
        return new MapResult(trips.size(), failed, new EnumMap<>(BotNavigationGraph.EdgeType.class));
    }

    private static String row(int mapId, int from, int to, String type, int stepX, Point start, Point end,
                              Attempt first, String verdict) {
        return mapId + "\t" + from + "\t" + to + "\t" + type + "\t" + stepX + "\t" + start.x + "\t" + start.y
                + "\t" + end.x + "\t" + end.y + "\t" + first.endRegion() + "\t" + first.end().x + "\t"
                + first.end().y + "\t" + first.maxMoved() + "\t" + verdict;
    }

    private record MapResult(int tested, int failed, Map<BotNavigationGraph.EdgeType, Integer> failedByType) {
    }

    private static MapResult sweepMap(int mapId, List<String> rows) {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
        BotMovementSimulationLab lab = BotMovementSimulationLab.fromMap(map);
        BotNavigationGraph graph = BotNavigationGraphProvider.getGraph(map);
        int tested = 0;
        int failed = 0;
        Map<BotNavigationGraph.EdgeType, Integer> byType = new EnumMap<>(BotNavigationGraph.EdgeType.class);
        int botId = 1;
        for (BotNavigationGraph.Region region : graph.regions) {
            if (region.isRopeRegion) {
                continue;
            }
            for (BotNavigationGraph.Edge edge : graph.getOutgoing(region.id)) {
                if (!TESTED.contains(edge.type) || edge.fromRegionId == edge.toRegionId) {
                    continue;
                }
                BotNavigationGraph.Region target = graph.getRegion(edge.toRegionId);
                if (target == null || target.isRopeRegion) {
                    continue;
                }
                if (edge.startPoint.distance(edge.endPoint) < MIN_EDGE_PX) {
                    continue; // the start already sits within arrival range of the end: nothing to test
                }
                String key = mapId + "\t" + edge.type + "\t" + edge.launchStepX + "\t" + edge.startPoint.x + "\t"
                        + edge.startPoint.y + "\t" + edge.endPoint.x + "\t" + edge.endPoint.y;
                if (only != null && !only.contains(key)) {
                    continue;
                }
                tested++;
                Attempt first = attempt(lab, map, graph, edge, botId++);
                if (first.reached() && only == null) {
                    rows.add(row(mapId, edge.fromRegionId, edge.toRegionId, edge.type.toString(), edge.launchStepX,
                            edge.startPoint, edge.endPoint, first, "ok"));
                    continue; // a passing move is written too, so a gate can see one a change removed
                }
                // The sim isn't fully deterministic (timer threads run beside the tick), so a move that
                // fails once is retried. Only a move that fails every try is stuck; one that passes on a
                // retry is reported as flaky and kept out of the gate.
                int failures = first.reached() ? 0 : 1;
                for (int retry = 1; retry < attempts; retry++) {
                    if (!attempt(lab, map, graph, edge, botId++).reached()) {
                        failures++;
                    }
                }
                String verdict = only != null ? "fails:" + failures + "/" + attempts
                        : failures < attempts ? "flaky:" + failures + "/" + attempts : first.verdict(edge);
                if (failures == attempts) {
                    failed++;
                    byType.merge(edge.type, 1, Integer::sum);
                }
                rows.add(row(mapId, edge.fromRegionId, edge.toRegionId, edge.type.toString(), edge.launchStepX,
                        edge.startPoint, edge.endPoint, first, verdict));
            }
        }
        return new MapResult(tested, failed, byType);
    }
}
