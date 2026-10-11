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
            BotFleetOps.save();
            BotFleetOps.tracks.clear();

            BotFleetOps.load(now + MIN);
            BotFleetOps.Track restored = BotFleetOps.tracks.get(254);
            assertEquals(41, restored.samples.size());
            assertEquals(40 * MIN, BotFleetOps.frozenForMs(restored));
            assertTrue(BotFleetOps.alerts(restored, now + MIN, "SipsBuddy1", "1").isEmpty(),
                    "the owner heard about it before the restart");
        } finally {
            BotFleetOps.tracks.clear();
            BotFleetOps.store = previousStore;
        }
    }
}
