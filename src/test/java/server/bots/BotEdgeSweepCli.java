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
 * stall there (issue #30 was one). Prints one TSV line per failed edge and a summary per map.
 *
 * <p>Usage: BotEdgeSweepCli out.tsv mapId [mapId ...]   (or @file with one map id per line)
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
        List<String> rows = new ArrayList<>();
        rows.add("map\tfrom\tto\ttype\tstepX\tstartX\tstartY\tendX\tendY\tendRegion\tfinalX\tfinalY\tmovedPx\tverdict");
        int totalTested = 0;
        int totalFailed = 0;
        for (int mapId : mapIds) {
            MapResult r;
            try {
                r = sweepMap(mapId, rows);
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
                tested++;
                String name = "sweep" + botId;
                BotEntry entry = lab.spawnBot(name, botId++, map, new Point(edge.startPoint));
                lab.teleport(name, new Point(edge.startPoint));
                lab.primeMapState(name);
                if (!routeMode) {
                    lab.setNavState(name, edge, edge.toRegionId, false);
                }
                lab.setMoveTarget(name, new Point(edge.endPoint), true);
                Point start = new Point(entry.bot.getPosition());
                int maxMoved = 0;
                boolean reached = false;
                int ticks = budgetMs / BotMovementManager.cfg.TICK_MS;
                String error = null;
                try {
                    for (int t = 0; t < ticks && !reached; t++) {
                        lab.step(1);
                        Point p = entry.bot.getPosition();
                        maxMoved = Math.max(maxMoved, (int) start.distance(p));
                        if (!entry.inAir && !entry.climbing
                                && (p.distance(edge.endPoint) <= MIN_EDGE_PX
                                || BotNavigationManager.resolveCurrentRegionId(graph, entry, map, p) == edge.toRegionId)) {
                            reached = true;
                        }
                    }
                } catch (RuntimeException e) {
                    error = e.getClass().getSimpleName();
                }
                lab.remove(name);
                if (!reached) {
                    failed++;
                    byType.merge(edge.type, 1, Integer::sum);
                    Point p = entry.bot.getPosition();
                    int endRegion = BotNavigationManager.resolveCurrentRegionId(graph, entry, map, p);
                    String verdict = error != null ? "error:" + error
                            : maxMoved < 12 ? "frozen" : endRegion == edge.fromRegionId ? "stayed" : "wrong-region";
                    rows.add(mapId + "\t" + edge.fromRegionId + "\t" + edge.toRegionId + "\t" + edge.type + "\t"
                            + edge.launchStepX + "\t" + edge.startPoint.x + "\t" + edge.startPoint.y + "\t"
                            + edge.endPoint.x + "\t" + edge.endPoint.y + "\t" + endRegion + "\t" + p.x + "\t" + p.y
                            + "\t" + maxMoved + "\t" + verdict);
                }
            }
        }
        return new MapResult(tested, failed, byType);
    }
}
