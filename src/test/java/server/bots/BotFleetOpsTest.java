package server.bots;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotFleetOpsTest {
    private static final long MIN = 60_000L;
    private IntFunction<String> previousNames;

    @BeforeEach
    void stubMapNames() {
        previousNames = BotFleetOps.mapNameLookup;
        BotFleetOps.mapNameLookup = id -> id == 600020100 ? "Deity Room" : id == 600000000 ? "New Leaf City" : "map " + id;
    }

    @AfterEach
    void restoreMapNames() {
        BotFleetOps.mapNameLookup = previousNames;
    }

    private static BotFleetOps.Sample still(long t, long exp) {
        return new BotFleetOps.Sample(t, 600020100, -1317, 156, exp, true, -1);
    }

    @Test
    void aBotThatStandsStillBusyAndEarnsNothingIsFrozenAndAlertsOnce() {
        BotFleetOps.Track t = new BotFleetOps.Track();
        for (int i = 0; i <= 10; i++) {
            BotFleetOps.record(t, still(i * MIN, 5_000));
        }
        assertEquals(10 * MIN, BotFleetOps.frozenForMs(t));
        List<String> first = BotFleetOps.alerts(t, 10 * MIN, "SipsBuddy26", "26");
        assertEquals(List.of("SipsBuddy26 frozen 10 min at Deity Room (x -1317, y 156). navi rescue 26"), first);
        BotFleetOps.record(t, still(11 * MIN, 5_000));
        assertTrue(BotFleetOps.alerts(t, 11 * MIN, "SipsBuddy26", "26").isEmpty(), "one whisper per incident");
    }

    @Test
    void earningExpOrMovingOrRestingIsNotFrozen() {
        BotFleetOps.Track gaining = new BotFleetOps.Track();
        BotFleetOps.Track resting = new BotFleetOps.Track();
        for (int i = 0; i <= 10; i++) {
            BotFleetOps.record(gaining, still(i * MIN, 5_000 + i));
            BotFleetOps.record(resting, new BotFleetOps.Sample(i * MIN, 104000000, 0, 0, 5_000, false, -1));
        }
        assertEquals(0L, BotFleetOps.frozenForMs(gaining));
        assertEquals(0L, BotFleetOps.frozenForMs(resting));
    }

    @Test
    void aThirdErrandToTheSameMapWithinTheWindowIsALoop() {
        BotFleetOps.Track t = new BotFleetOps.Track();
        // SipsBuddy26: out to New Leaf City every 12 minutes, two samples on the errand each time.
        for (long m = 0; m <= 26; m++) {
            int errand = m % 12 <= 1 ? 600000000 : -1;
            BotFleetOps.record(t, new BotFleetOps.Sample(m * MIN, 103000101, (int) m * 50, 0, 1_000 + m, true, errand));
        }
        assertEquals(3, BotFleetOps.errandStarts(t, 600000000, 26 * MIN));
        List<String> alerts = BotFleetOps.alerts(t, 26 * MIN, "SipsBuddy26", "26");
        assertEquals(List.of("SipsBuddy26 started 3 errands to New Leaf City in 45 min. navi why 26"), alerts);
        // Long after, the old starts have aged out of the window.
        assertEquals(0, BotFleetOps.errandStarts(t, 600000000, 26 * MIN + 2 * BotFleetOps.LOOP_WINDOW_MS));
    }

    @Test
    void expPerHourNeedsTenMinutesAndCountsADeathAsNoGain() {
        BotFleetOps.Track t = new BotFleetOps.Track();
        BotFleetOps.record(t, still(0, 1_000));
        BotFleetOps.record(t, still(5 * MIN, 2_000));
        assertNull(BotFleetOps.expPerHour(t, 5 * MIN));
        BotFleetOps.record(t, still(10 * MIN, 1_500)); // died: lost 500
        BotFleetOps.record(t, still(20 * MIN, 3_500));
        assertEquals((1_000 + 2_000) * 3.0, BotFleetOps.expPerHour(t, 20 * MIN), 1e-6);
    }

    @Test
    void aBotNumberMatchesOnlyTheNameThatEndsInExactlyThatNumber() {
        List<String> names = List.of("SipsBuddy2", "SipsBuddy26", "SipsBuddy126", "Sipsaeki");
        assertEquals(List.of("SipsBuddy26"), BotFleetOps.resolve(names, "26"));
        assertEquals(List.of("SipsBuddy2"), BotFleetOps.resolve(names, "sipsbuddy2"));
        assertEquals(List.of(), BotFleetOps.resolve(names, "7"));
        assertEquals("26", BotFleetOps.shortName("SipsBuddy26"));
        assertEquals("Sipsaeki", BotFleetOps.shortName("Sipsaeki"));
    }

    @Test
    void totalExpAddsEveryEarlierLevel() {
        assertEquals(BotFleetOps.totalExp(10, 0) + 5, BotFleetOps.totalExp(10, 5));
        assertEquals(constants.game.ExpTable.getExpNeededForLevel(10), BotFleetOps.totalExp(11, 0) - BotFleetOps.totalExp(10, 0));
        assertEquals("1.1M", BotFleetOps.fmt(1_124_210));
        assertEquals("76k", BotFleetOps.fmt(76_224));
    }

    @Test
    void onlyFleetWordsAndActionsAreFleetCommands() {
        assertTrue(BotFleetOps.isHelp(""));
        assertTrue(BotFleetOps.isHelp("help"));
        assertNull(BotFleetOps.handle(null, "email"));
    }

    @Test
    void aBusyBotThatMovesButEarnsNothingForHalfAnHourAlertsOnce() {
        // SipsBuddy1: a different shop map every few minutes, never frozen, never the same errand thrice.
        BotFleetOps.Track t = new BotFleetOps.Track();
        int[] shops = {103000000, 200000111, 670000100, 600000000};
        for (int m = 0; m <= 30; m++) {
            BotFleetOps.record(t, new BotFleetOps.Sample(m * MIN, shops[(m / 4) % 4], m * 40, 0, 9_000, true, -1));
        }
        assertEquals(0L, BotFleetOps.frozenForMs(t));
        assertEquals(30 * MIN, BotFleetOps.noExpForMs(t));
        assertEquals(List.of("SipsBuddy1 busy 30 min with no EXP, now at New Leaf City. navi why 1"),
                BotFleetOps.alerts(t, 30 * MIN, "SipsBuddy1", "1"));
        BotFleetOps.record(t, new BotFleetOps.Sample(31 * MIN, 103000000, 0, 0, 9_000, true, -1));
        assertTrue(BotFleetOps.alerts(t, 31 * MIN, "SipsBuddy1", "1").isEmpty(), "one whisper per incident");
        BotFleetOps.record(t, new BotFleetOps.Sample(32 * MIN, 103000000, 0, 0, 9_400, true, -1));
        assertEquals(0L, BotFleetOps.noExpForMs(t), "any gain ends the incident");
    }

    @Test
    void aFrozenBotIsNotWhisperedTwiceForEarningNothing() {
        BotFleetOps.Track t = new BotFleetOps.Track();
        for (int i = 0; i <= 30; i++) {
            BotFleetOps.record(t, still(i * MIN, 5_000));
            List<String> alerts = BotFleetOps.alerts(t, i * MIN, "SipsBuddy26", "26");
            assertTrue(alerts.stream().noneMatch(a -> a.contains("no EXP")), alerts.toString());
        }
    }

    @Test
    void aBotFrozenTwentyMinutesIsRescuedThenTheClockStartsOver() {
        BotFleetOps.Track t = new BotFleetOps.Track();
        for (int i = 0; i <= 19; i++) {
            BotFleetOps.record(t, still(i * MIN, 5_000));
            assertNull(BotFleetOps.autoRescue(t, i * MIN), "not before 20 min, at " + i);
        }
        BotFleetOps.record(t, still(20 * MIN, 5_000));
        assertEquals(new BotFleetOps.AutoRescue(true, "frozen 20 min"), BotFleetOps.autoRescue(t, 20 * MIN));
        // Still on the spot a minute later (the scroll waits for the bot's tick): no second decision.
        BotFleetOps.record(t, still(21 * MIN, 5_000));
        assertNull(BotFleetOps.autoRescue(t, 21 * MIN));
        assertEquals(MIN, BotFleetOps.frozenForMs(t), "the clock restarted at the rescue");
        assertTrue(BotFleetOps.expPerHour(t, 21 * MIN) != null, "the EXP history is kept");
    }

    @Test
    void aSpotThatKeepsRefreezingGetsTwoRescuesThenOneWhisperAndStops() {
        BotFleetOps.Track t = new BotFleetOps.Track();
        int rescues = 0;
        int gaveUp = 0;
        for (int i = 0; i <= 100; i++) {
            BotFleetOps.record(t, still(i * MIN, 5_000));
            BotFleetOps.AutoRescue ar = BotFleetOps.autoRescue(t, i * MIN);
            if (ar != null && ar.rescue()) rescues++;
            if (ar != null && !ar.rescue()) gaveUp++;
        }
        assertEquals(BotFleetOps.AUTO_RESCUE_MAX, rescues);
        assertEquals(1, gaveUp);
    }

    @Test
    void noExpRescuesOnlyABotOnItsOwnMapPlanNotOneOnAnErrand() {
        BotFleetOps.Track grinding = new BotFleetOps.Track();
        BotFleetOps.Track errand = new BotFleetOps.Track();
        for (int i = 0; i <= 45; i++) {
            // SipsBuddy26 on 800030000: walking the map, through its portals, never killing.
            BotFleetOps.record(grinding, new BotFleetOps.Sample(i * MIN, 800030000, (i % 2) * 300, 0, 8_000, true, -1));
            BotFleetOps.record(errand, new BotFleetOps.Sample(i * MIN, 101000300 + i % 3, i * 40, 0, 8_000, true, 101000300));
        }
        assertEquals(new BotFleetOps.AutoRescue(true, "busy 45 min with no EXP"), BotFleetOps.autoRescue(grinding, 45 * MIN));
        assertNull(BotFleetOps.autoRescue(errand, 45 * MIN), "a scroll would only restart an errand loop");
    }

    /** Feed one sample a minute and refresh the pace, as the sampler does. */
    private static BotFleetOps.AutoRescue tick(BotFleetOps.Track t, BotFleetOps.Sample s, double bandMedian) {
        BotFleetOps.record(t, s);
        BotFleetOps.updatePace(t, s.t(), 52, bandMedian);
        return BotFleetOps.autoRescue(t, s.t());
    }

    @Test
    void aBotHoveringFarUnderItsUsualPaceIsRescuedThoughItNeverFreezesOrStopsEarning() {
        // SipsBuddy3 on 2026-10-10: about 220k EXP/h, then 11k EXP/h twitching 20 px on Haunted House 682000100.
        BotFleetOps.Track t = new BotFleetOps.Track();
        long exp = 7_000_000;
        int rescuedAt = -1;
        String why = null;
        for (int m = 0; m <= 30; m++, exp += 3_667) {
            assertNull(tick(t, new BotFleetOps.Sample(m * MIN, 682010202, m * 37, 100, exp, true, -1), 0));
        }
        for (int m = 31; m <= 80; m++, exp += 183) {
            BotFleetOps.AutoRescue ar = tick(t, new BotFleetOps.Sample(m * MIN, 682000100, 200 + (m % 2) * 20, 79, exp, true, -1), 0);
            assertEquals(0L, BotFleetOps.frozenForMs(t), "twitching 20 px is not frozen");
            assertTrue(BotFleetOps.noExpForMs(t) < BotFleetOps.NO_EXP_LIST_MS, "it keeps earning a little");
            if (ar != null && rescuedAt == -1) {
                rescuedAt = m;
                why = ar.why();
            }
        }
        assertTrue(rescuedAt >= 45 && rescuedAt <= 65, "rescued at minute " + rescuedAt);
        assertTrue(why.startsWith("earning ") && why.contains("usual 2"), why);
    }

    @Test
    void zeroEarningIsTheNoExpChecksJobAndASlowStartUsesTheLevelBand() {
        BotFleetOps.Track idle = new BotFleetOps.Track();
        BotFleetOps.Track slowSinceLogin = new BotFleetOps.Track();
        long exp = 5_000_000;
        BotFleetOps.AutoRescue slowRescue = null;
        for (int m = 0; m <= 35; m++, exp += 183) {
            tick(idle, new BotFleetOps.Sample(m * MIN, 682000100, (m % 2) * 20, 79, 5_000_000, true, -1), 0);
            assertEquals(0L, BotFleetOps.lowExpForMs(idle, m * MIN), "zero is no EXP, not low");
            BotFleetOps.AutoRescue ar = tick(slowSinceLogin,
                    new BotFleetOps.Sample(m * MIN, 682000100, (m % 2) * 20, 79, exp, true, -1), 220_000);
            if (ar != null && slowRescue == null) {
                slowRescue = ar;
            }
        }
        // Half the band median (110k) is the bar when the bot never showed its own pace: 11k is under a fifth.
        assertTrue(slowRescue != null && slowRescue.rescue() && slowRescue.why().contains("usual 110k"),
                String.valueOf(slowRescue));
    }

    @Test
    void aSlowWalkAcrossMapsIsNotScrolledAndASlowErrandGoesToTheResponder() {
        BotFleetOps.Track walk = new BotFleetOps.Track();
        BotFleetOps.Track errand = new BotFleetOps.Track();
        long exp = 5_000_000;
        for (int m = 0; m <= 40; m++, exp += 183) {
            BotFleetOps.AutoRescue ar = tick(walk, new BotFleetOps.Sample(m * MIN, 101000000 + m / 5, m * 40, 0, exp, true, -1), 220_000);
            assertNull(ar, "a scroll would only send a walking bot back to town");
            tick(errand, new BotFleetOps.Sample(m * MIN, 200000111, m * 40, 0, exp, true, 101000300), 220_000);
        }
        assertTrue(BotFleetOps.lowExpForMs(walk, 40 * MIN) >= BotFleetOps.LOW_EXP_ACT_MS);
        assertEquals("low_exp_on_errand", BotFleetOps.incidentKind(errand, 40 * MIN));
    }

    @Test
    void theResponderGetsOnlyStallsAScrollCannotFix() {
        BotFleetOps.Track loop = new BotFleetOps.Track();
        BotFleetOps.Track errandNoExp = new BotFleetOps.Track();
        BotFleetOps.Track frozen = new BotFleetOps.Track();
        for (long m = 0; m <= 35; m++) {
            int errand = m % 12 <= 1 ? 600000000 : -1; // SipsBuddy26's ten minute weapon trips
            BotFleetOps.record(loop, new BotFleetOps.Sample(m * MIN, 103000101, (int) m * 50, 0, 1_000 + m, true, errand));
            BotFleetOps.record(errandNoExp, new BotFleetOps.Sample(m * MIN, 200000111 + (int) (m % 3), (int) m * 40, 0, 9_000, true, 101000300));
            BotFleetOps.record(frozen, still(m * MIN, 5_000));
        }
        // the loop is mid errand at minute 24 (24 % 12 == 0)
        assertEquals("errand_loop", BotFleetOps.incidentKind(trimTo(loop, 24), 24 * MIN));
        assertEquals("no_exp_on_errand", BotFleetOps.incidentKind(errandNoExp, 35 * MIN));
        assertNull(BotFleetOps.incidentKind(frozen, 35 * MIN), "auto rescue owns a frozen bot first");
        frozen.autoRescueGaveUpAlerted = true;
        assertEquals("refreezing", BotFleetOps.incidentKind(frozen, 35 * MIN));
    }

    private static BotFleetOps.Track trimTo(BotFleetOps.Track src, long minute) {
        BotFleetOps.Track t = new BotFleetOps.Track();
        for (BotFleetOps.Sample s : src.samples) {
            if (s.t() <= minute * MIN) {
                BotFleetOps.record(t, s);
            }
        }
        return t;
    }

    @Test
    void holdErrandsDropsTheTripAndUsesTheErrandCooldown() {
        BotEntry entry = org.mockito.Mockito.mock(BotEntry.class);
        entry.autopilotErrandMapId = 600000000;
        entry.autopilotNextErrandAtMs = 0L;
        BotFleetOps.holdErrands(entry, 1_000_000L, 45);
        assertEquals(-1, entry.autopilotErrandMapId);
        assertTrue(entry.autopilotReturningFromErrand);
        assertEquals(1_000_000L + 45 * MIN, entry.autopilotNextErrandAtMs);
        BotFleetOps.holdErrands(entry, 1_000_000L, 15);
        assertEquals(1_000_000L + 45 * MIN, entry.autopilotNextErrandAtMs, "a shorter hold never cuts a longer one");
    }

    @Test
    void aTaskQueuedForTheNextTickRunsOnceAndAFailureStaysContained() {
        BotEntry entry = org.mockito.Mockito.mock(BotEntry.class);
        int[] runs = {0};
        entry.nextTickTask = () -> runs[0]++;
        BotManager.runNextTickTask(entry);
        BotManager.runNextTickTask(entry);
        assertEquals(1, runs[0]);
        entry.nextTickTask = () -> { throw new IllegalStateException("boom"); };
        BotManager.runNextTickTask(entry);
        assertNull(entry.nextTickTask);
    }

    @Test
    void samplesSurviveARestartAndOngoingIncidentsStayQuiet(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        java.nio.file.Path previousStore = BotFleetOps.store;
        BotFleetOps.store = dir.resolve("fleet-ops").resolve("samples.tsv");
        try {
            BotFleetOps.tracks.clear();
            long now = 100 * MIN;
            BotFleetOps.Track t = BotFleetOps.tracks.computeIfAbsent(254, id -> new BotFleetOps.Track());
            for (int i = 0; i <= 40; i++) {
                BotFleetOps.record(t, still(now - (40 - i) * MIN, 9_000));
            }
            t.paceExpPerHour = 220_000;
            t.paceAtMs = now;
            t.level = 52;
            BotFleetOps.save();
            BotFleetOps.tracks.clear();

            BotFleetOps.load(now + MIN);
            BotFleetOps.Track restored = BotFleetOps.tracks.get(254);
            assertEquals(41, restored.samples.size());
            assertEquals(40 * MIN, BotFleetOps.frozenForMs(restored));
            assertEquals(220_000, restored.paceExpPerHour, 1_000, "the usual pace outlives the restart");
            assertEquals(52, restored.level);
            assertTrue(BotFleetOps.alerts(restored, now + MIN, "SipsBuddy1", "1").isEmpty(),
                    "the owner heard about it before the restart");
        } finally {
            BotFleetOps.tracks.clear();
            BotFleetOps.store = previousStore;
        }
    }
}
