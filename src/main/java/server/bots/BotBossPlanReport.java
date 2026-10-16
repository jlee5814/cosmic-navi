package server.bots;

import client.Character;
import client.inventory.Equip;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.wz.WZFiles;
import server.ItemInformationProvider;
import server.bots.combat.BotDefenseDataProvider;
import server.life.LifeFactory;
import server.life.Monster;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which boss runs pay back for a party of online bots, and what each member needs first to survive one. For every
 * boss on a field the party can travel to: each member's damage per second on it ({@link BotGrindAdvisor#killProfile},
 * the grind planner's kill model), its hardest landed touch ({@link BotDefenseDataProvider#physicalTouchDamageAt} at
 * the top roll, the damage bots actually take) against its HP, and its round trip. Members who reach it, hurt it and
 * live through two hardest touches fight; the rest are told what they need. A run costs the fighters' round trip plus
 * the party kill time, in the experience each would have ground meanwhile; it returns the boss's experience by damage
 * share and its gear drops, each judged for the member who gains most by the gear detour rule ({@link
 * BotGearPayback#judge}) over the expected runs to see one, net of the experience those runs earn, with the wear time
 * cut by the hours spent waiting on respawns. Read only; served at {@code /api/bossplan}.
 */
final class BotBossPlanReport {
    private static final Logger log = LoggerFactory.getLogger(BotBossPlanReport.class);

    private BotBossPlanReport() {}

    // The top of the touch damage range (rolls are drawn in [0, 1)).
    private static final double TOP_ROLL = 0.999;

    /** Where a boss lives and how long it takes to come back: Map.wz life ({@code mobTime}, seconds from a kill) or an
     *  area boss event script (the period it rechecks and respawns on). */
    record Site(int mobId, int mapId, double respawnHours, String source) {}

    /** One member against one boss. {@code tripHours} is infinite when the member can't get there. */
    record Fighter(int botId, String name, double dps, int maxHit, int hp, double tripHours) {
        boolean reaches() {
            return Double.isFinite(tripHours);
        }

        boolean hurts() {
            return dps > 0;
        }

        /** Lives through two landed hardest touches: a pot lands between hits, but a second touch inside the hit
         *  cooldown or a summoned add doesn't wait for one. */
        boolean survives() {
            return hp > 2L * maxHit;
        }

        int needHp() {
            return survives() ? 0 : 2 * maxHit + 1;
        }

        boolean fights() {
            return reaches() && hurts() && survives();
        }

        String need() {
            return !reaches() ? "can't reach" : !hurts() ? "can't hurt it" : !survives()
                    ? needHp() + " HP (has " + hp + ")" : "ready";
        }
    }

    /** A drop a fighter would take: the expected runs to see one, the hours those runs span with respawns, and the
     *  taker's gear detour verdict, whose wear is cut by the waiting. */
    record Drop(int itemId, int takerId, double chance, double runs, double calendarHours, BotGearPayback.Verdict verdict,
                double wearLeftHours) {
        boolean worth() {
            return verdict.paybackHours() < wearLeftHours;
        }
    }

    /** One boss run for the party. Kill time is infinite when no member fights. */
    record Run(Site site, String name, int level, long hp, List<Fighter> fighters, double killSeconds, double runHours,
               double partyExp, double forgoneExp, List<Drop> drops) {
        boolean killable() {
            return Double.isFinite(killSeconds);
        }

        /** The run out earns the grinding it replaces on experience alone. */
        boolean expWorth() {
            return killable() && partyExp > forgoneExp;
        }

        Drop bestDrop() {
            return drops.isEmpty() ? null : drops.get(0);
        }

        boolean worth() {
            return expWorth() || (bestDrop() != null && bestDrop().worth());
        }

        double expRatio() {
            return killable() && forgoneExp > 0 ? partyExp / forgoneExp : 0;
        }
    }

    /** A party member as the plan sees it: its grind map, the experience per hour it gives up, and travel times. */
    record Member(BotEntry entry, Character bot, BotGrindPlanner.MobCandidate grind, double expPerHour,
                  Map<Integer, Double> floodSeconds) {}

    static Member member(BotEntry entry) {
        Character bot = entry.bot;
        BotGrindPlanner.MobCandidate grind = BotGearPlanReport.grindMap(entry, bot);
        double exp = grind == null ? 0 : grind.exp() * BotGrindPlanner.killsPerHour(grind);
        return new Member(entry, bot, grind, exp, BotTravelCost.floodSeconds(bot.getMapId(),
                BotShopManager.GEAR_TRIP_MAX_HOPS, BotAutopilotManager.travelOptions(bot, true), ms -> ms));
    }

    // ---- where bosses live ----

    private static volatile List<Site> sites;

    /** Every boss site on a field (not a town, not instanced space): Map.wz spawns and area boss scripts. Static
     *  data, read once. */
    static List<Site> sites() {
        List<Site> cached = sites;
        if (cached != null) {
            return cached;
        }
        synchronized (BotBossPlanReport.class) {
            if (sites == null) {
                List<Site> all = new ArrayList<>(wzSites());
                all.addAll(scriptSites(Path.of("scripts", "event")));
                sites = List.copyOf(all);
            }
            return sites;
        }
    }

    private static List<Site> wzSites() {
        BotSpawnIndex.Index index = BotSpawnIndex.get();
        DataProvider maps = DataProviderFactory.getDataProvider(WZFiles.MAP);
        List<Site> out = new ArrayList<>();
        for (Map.Entry<Integer, List<BotSpawnIndex.SpawnSite>> e : index.byMob().entrySet()) {
            if (!isBoss(e.getKey())) {
                continue;
            }
            for (BotSpawnIndex.SpawnSite s : e.getValue()) {
                if (field(index, s.mapId())) {
                    out.add(new Site(e.getKey(), s.mapId(),
                            BotSpawnIndex.mobTimeSeconds(maps, s.mapId(), e.getKey()) / 3600.0, "map"));
                }
            }
        }
        return out;
    }

    private static List<Site> scriptSites(Path dir) {
        List<Site> out = new ArrayList<>();
        BotSpawnIndex.Index index = BotSpawnIndex.get();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.js")) {
            for (Path f : files) {
                Site s = scriptSite(Files.readString(f, StandardCharsets.UTF_8));
                if (s != null && isBoss(s.mobId()) && field(index, s.mapId())) {
                    out.add(s);
                }
            }
        } catch (IOException e) {
            log.warn("Boss plan: can't read event scripts in {}", dir, e);
        }
        return out;
    }

    private static final Pattern GET_MAP = Pattern.compile("getMap\\((\\d+)\\)");
    private static final Pattern GET_MOB = Pattern.compile("getMonster\\((\\d+)\\)");
    private static final Pattern RECHECK = Pattern.compile("schedule\\(\\s*\"start\"\\s*,\\s*([0-9*\\s]+)\\)");

    /** The site an event script spawns a monster on: exactly one map and one monster, rescheduled on a fixed period
     *  ({@code em.schedule("start", 3 * 60 * 60 * 1000)}); null for any other script. Pure. */
    static Site scriptSite(String js) {
        Set<Integer> maps = ids(GET_MAP, js);
        Set<Integer> mobs = ids(GET_MOB, js);
        if (maps.size() != 1 || mobs.size() != 1) {
            return null;
        }
        long periodMs = 0;
        for (Matcher m = RECHECK.matcher(js); m.find(); ) {
            long product = 1;
            for (String factor : m.group(1).split("\\*")) {
                product *= Long.parseLong(factor.trim());
            }
            periodMs = Math.max(periodMs, product);
        }
        return periodMs <= 0 ? null : new Site(mobs.iterator().next(), maps.iterator().next(), periodMs / 3_600_000.0, "event");
    }

    private static Set<Integer> ids(Pattern p, String js) {
        Set<Integer> out = new LinkedHashSet<>();
        for (Matcher m = p.matcher(js); m.find(); ) {
            out.add(Integer.parseInt(m.group(1)));
        }
        return out;
    }

    private static boolean isBoss(int mobId) {
        try {
            Monster m = LifeFactory.getMonster(mobId);
            return m != null && m.getStats() != null && m.getStats().isBoss() && !m.getStats().isFriendly()
                    && m.getStats().getExp() > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean field(BotSpawnIndex.Index index, int mapId) {
        BotSpawnIndex.MapSpawns map = index.byMap().get(mapId);
        return mapId < BotGrindAdvisor.INSTANCED_MAPID_FLOOR && (map == null || !map.town());
    }

    // ---- the plan ----

    /** Every boss some member can reach, worth it first, then the best experience return. */
    static List<Run> plan(List<Member> party, ItemInformationProvider ii) {
        List<Run> runs = new ArrayList<>();
        Set<Long> seen = new java.util.HashSet<>();
        for (Site s : sites()) {
            if (!seen.add(((long) s.mobId() << 32) | s.mapId())
                    || party.stream().noneMatch(m -> m.floodSeconds().containsKey(s.mapId()))) {
                continue;
            }
            Monster mob = LifeFactory.getMonster(s.mobId());
            if (mob != null) {
                runs.add(run(party, ii, s, mob));
            }
        }
        runs.sort(Comparator.comparing(Run::worth).reversed().thenComparing(Comparator.comparingDouble(Run::expRatio).reversed()));
        return runs;
    }

    static Run run(List<Member> party, ItemInformationProvider ii, Site s, Monster mob) {
        BotDefenseDataProvider defense = BotDefenseDataProvider.getInstance();
        long hp = Math.max(1, mob.getMaxHp());
        List<Fighter> fighters = new ArrayList<>();
        double dpsSum = 0;
        double trip = 0;
        for (Member m : party) {
            double[] kp = BotGrindAdvisor.killProfile(m.entry(), m.bot(), mob);
            Double oneWay = m.floodSeconds().get(s.mapId());
            Fighter f = new Fighter(m.bot().getId(), m.bot().getName(), kp == null || kp[0] <= 0 ? 0 : hp / kp[0],
                    defense.physicalTouchDamageAt(m.bot(), mob.getPADamage(), mob.getLevel(), TOP_ROLL),
                    BotDefenseDataProvider.effectiveMaxHp(m.bot()),
                    oneWay == null ? Double.POSITIVE_INFINITY : oneWay * 2.0 / 3600.0);
            fighters.add(f);
            if (f.fights()) {
                dpsSum += f.dps();
                trip = Math.max(trip, f.tripHours());
            }
        }
        double kill = dpsSum > 0 ? hp / dpsSum : Double.POSITIVE_INFINITY;
        double runHours = trip + kill / 3600.0;
        Map<Integer, Double> expById = new HashMap<>();
        double partyExp = 0;
        double forgone = 0;
        for (int i = 0; i < party.size(); i++) {
            Fighter f = fighters.get(i);
            if (!f.fights()) {
                continue;
            }
            Member m = party.get(i);
            double exp = mob.getStats().getExp() * m.bot().getExpRate() * f.dps() / dpsSum; // exp is split by damage
            expById.put(f.botId(), exp);
            partyExp += exp;
            forgone += m.expPerHour() * runHours;
        }
        List<Drop> drops = dpsSum > 0 ? drops(party, fighters, ii, s, runHours, expById) : List.of();
        return new Run(s, mob.getName(), mob.getLevel(), hp, fighters, kill, runHours, partyExp, forgone, drops);
    }

    private static List<Drop> drops(List<Member> party, List<Fighter> fighters, ItemInformationProvider ii, Site s,
                                    double runHours, Map<Integer, Double> expById) {
        List<Drop> out = new ArrayList<>();
        Member looter = null;
        for (int i = 0; i < party.size() && looter == null; i++) {
            looter = fighters.get(i).fights() ? party.get(i) : null;
        }
        Map<Integer, Map<Short, Double>> bars = new HashMap<>();
        for (int[] d : BotGrindAdvisor.gearDrops(s.mobId())) {
            double chance = Math.min(1.0, d[1] * (double) looter.bot().getDropRate() / BotGrindAdvisor.DROP_CHANCE_DENOMINATOR);
            if (chance <= 0) {
                continue;
            }
            double runs = 1.0 / chance;
            double calendar = runs * Math.max(runHours, s.respawnHours());
            Drop best = null;
            for (int i = 0; i < party.size(); i++) {
                Member m = party.get(i);
                Equip clean = fighters.get(i).fights() ? BotGearPlanReport.wearableNow(m.bot(), ii, d[0]) : null;
                if (clean == null) {
                    continue;
                }
                double gain = BotGrindAdvisor.catalogAcquireGain(m.bot(), ii, d[0],
                        bars.computeIfAbsent(m.bot().getId(), k -> new HashMap<>()));
                if (gain <= 0) {
                    continue;
                }
                // Each run gives back the experience it earns, in hours of this member's grinding.
                double earned = m.expPerHour() > 0 ? expById.getOrDefault(m.bot().getId(), 0.0) / m.expPerHour() : 0;
                BotGearPayback.Verdict v = BotGearPayback.judge(m.bot(), ii, clean, gain,
                        runs * Math.max(0, runHours - earned), m.grind());
                Drop drop = new Drop(d[0], m.bot().getId(), chance, runs, calendar, v,
                        v.wearHours() - Math.max(0, calendar - runs * runHours));
                if (best == null || DROP_ORDER.compare(drop, best) < 0) {
                    best = drop;
                }
            }
            if (best != null) {
                out.add(best);
            }
        }
        out.sort(DROP_ORDER);
        return out;
    }

    /** Worth it first, then the fastest payback. */
    static final Comparator<Drop> DROP_ORDER = Comparator.comparing(Drop::worth).reversed()
            .thenComparingDouble(d -> d.verdict().paybackHours());

    // ---- JSON ----

    static String json(List<BotEntry> online, List<Integer> offline) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        List<Member> party = new ArrayList<>();
        for (BotEntry e : online) {
            party.add(member(e));
        }
        List<String> members = new ArrayList<>();
        for (Member m : party) {
            members.add("{\"id\":" + m.bot().getId() + ",\"n\":" + str(m.bot().getName()) + ",\"level\":" + m.bot().getLevel()
                    + ",\"job\":" + (m.bot().getJob() == null ? 0 : m.bot().getJob().getId()) + ",\"hp\":"
                    + BotDefenseDataProvider.effectiveMaxHp(m.bot()) + ",\"grind\":" + (m.grind() == null ? "null"
                    : "{\"n\":" + str(m.grind().mapName()) + ",\"mob\":" + str(m.grind().mobName()) + ",\"expPerHour\":"
                    + Math.round(m.expPerHour()) + "}") + "}");
        }
        List<String> rows = new ArrayList<>();
        for (Run r : plan(party, ii)) {
            rows.add(row(ii, r));
        }
        return "{\"party\":[" + String.join(",", members) + "],\"offline\":" + offline + ",\"bosses\":["
                + String.join(",", rows) + "]}";
    }

    private static String row(ItemInformationProvider ii, Run r) {
        List<String> fs = new ArrayList<>();
        for (Fighter f : r.fighters()) {
            fs.add("{\"id\":" + f.botId() + ",\"n\":" + str(f.name()) + ",\"dps\":" + Math.round(f.dps()) + ",\"maxHit\":"
                    + f.maxHit() + ",\"hp\":" + f.hp() + ",\"trip\":" + hours(f.tripHours()) + ",\"need\":" + str(f.need()) + "}");
        }
        List<String> ds = new ArrayList<>();
        r.drops().stream().limit(3).forEach(d -> ds.add("{\"id\":" + d.itemId() + ",\"n\":" + str(ii.getName(d.itemId()))
                + ",\"taker\":" + d.takerId() + ",\"chance\":" + Math.round(d.chance() * 1e5) / 1e5 + ",\"runs\":"
                + Math.round(d.runs() * 10) / 10.0 + ",\"days\":" + hours(d.calendarHours() / 24.0) + ",\"payback\":"
                + hours(d.verdict().paybackHours()) + ",\"wearLeft\":" + hours(d.wearLeftHours()) + ",\"worth\":"
                + d.worth() + "}"));
        return "{\"mob\":" + r.site().mobId() + ",\"n\":" + str(r.name()) + ",\"level\":" + r.level() + ",\"hp\":" + r.hp()
                + ",\"map\":" + r.site().mapId() + ",\"mapName\":" + str(BotGrindAdvisor.mapName(r.site().mapId()))
                + ",\"respawnHours\":" + hours(r.site().respawnHours()) + ",\"source\":" + str(r.site().source())
                + ",\"killMinutes\":" + hours(r.killSeconds() / 60.0) + ",\"runHours\":" + hours(r.runHours())
                + ",\"partyExp\":" + Math.round(r.partyExp()) + ",\"forgoneExp\":" + Math.round(r.forgoneExp())
                + ",\"expWorth\":" + r.expWorth() + ",\"worth\":" + r.worth()
                + ",\"fighters\":[" + String.join(",", fs) + "],\"drops\":[" + String.join(",", ds) + "]}";
    }

    private static String hours(double h) {
        return Double.isFinite(h) ? String.valueOf(Math.round(h * 100) / 100.0) : "null";
    }

    private static String str(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
