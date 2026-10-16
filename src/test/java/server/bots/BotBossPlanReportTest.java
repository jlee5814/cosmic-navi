package server.bots;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotBossPlanReportTest {
    @Test
    void anAreaBossScriptGivesItsMapMonsterAndRecheckPeriod() {
        String mano = "function scheduleNew() { setupTask = em.schedule(\"start\", 0); }\n"
                + "function start() { var m = em.getChannelServer().getMapFactory().getMap(104000400);\n"
                + "  var mano = LifeFactory.getMonster(2220000);\n"
                + "  if (m.getMonsterById(2220000) != null) { em.schedule(\"start\", 3 * 60 * 60 * 1000); return; }\n"
                + "  em.schedule(\"start\", 3 * 60 * 60 * 1000); }";
        BotBossPlanReport.Site s = BotBossPlanReport.scriptSite(mano);
        assertEquals(2220000, s.mobId());
        assertEquals(104000400, s.mapId());
        assertEquals(3.0, s.respawnHours(), 1e-9);
        assertEquals("event", s.source());
        // Two maps, no monster, or no fixed period: not a single field boss.
        assertNull(BotBossPlanReport.scriptSite("getMap(1) getMap(2) getMonster(3) em.schedule(\"start\", 1000)"));
        assertNull(BotBossPlanReport.scriptSite("getMap(1) em.schedule(\"start\", 1000)"));
        assertNull(BotBossPlanReport.scriptSite("getMap(1) getMonster(3) em.schedule(\"start\", 0)"));
    }

    @Test
    void aMemberFightsOnlyWhenItReachesHurtsAndLivesThroughTwoHardestTouches() {
        BotBossPlanReport.Fighter ready = new BotBossPlanReport.Fighter(1, "a", 900, 1200, 2500, 0.3);
        assertTrue(ready.fights());
        assertEquals("ready", ready.need());
        BotBossPlanReport.Fighter frail = new BotBossPlanReport.Fighter(2, "b", 900, 1200, 2000, 0.3);
        assertFalse(frail.fights());
        assertEquals(2401, frail.needHp());
        assertEquals("2401 HP (has 2000)", frail.need());
        assertEquals("can't hurt it", new BotBossPlanReport.Fighter(3, "c", 0, 10, 5000, 0.3).need());
        assertEquals("can't reach", new BotBossPlanReport.Fighter(4, "d", 900, 10, 5000, Double.POSITIVE_INFINITY).need());
    }

    @Test
    void aRunIsWorthItOnExperienceAloneOrForADropThatRepaysBeforeItsWearRunsOut() {
        BotBossPlanReport.Site site = new BotBossPlanReport.Site(1, 2, 3.0, "event");
        BotGearPayback.Verdict quick = new BotGearPayback.Verdict(4, 500_000, 550_000, 40, 60);
        BotBossPlanReport.Drop waits = new BotBossPlanReport.Drop(9, 1, 0.1, 10, 30, quick, 35);
        BotBossPlanReport.Drop outlived = new BotBossPlanReport.Drop(9, 1, 0.01, 100, 300, quick, -200);
        assertTrue(waits.worth());
        assertFalse(outlived.worth()); // the respawn waits eat the wear before the drop shows up

        BotBossPlanReport.Run richExp = new BotBossPlanReport.Run(site, "x", 50, 1, List.of(), 60, 0.5, 400_000,
                300_000, List.of());
        assertTrue(richExp.expWorth());
        BotBossPlanReport.Run forDrop = new BotBossPlanReport.Run(site, "x", 50, 1, List.of(), 60, 0.5, 100_000,
                300_000, List.of(waits));
        assertFalse(forDrop.expWorth());
        assertTrue(forDrop.worth());
        BotBossPlanReport.Run unkillable = new BotBossPlanReport.Run(site, "x", 50, 1, List.of(),
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0, 0, List.of());
        assertFalse(unkillable.worth());
        assertEquals(List.of(waits, outlived), List.of(outlived, waits).stream().sorted(BotBossPlanReport.DROP_ORDER).toList());
    }
}
