package server.bots;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotTrainingPlanTest {

    private final java.util.function.BiConsumer<Integer, String> previousSave = BotTrainingPlan.saveConfig;
    private final java.util.function.IntConsumer previousLogout = BotTrainingPlan.logout;
    private final java.util.function.IntFunction<String> previousLoad = BotTrainingPlan.loadConfig;
    private final List<String> saved = new ArrayList<>();
    private final List<Integer> loggedOut = new ArrayList<>();

    @AfterEach
    void restore() {
        BotTrainingPlan.saveConfig = previousSave;
        BotTrainingPlan.logout = previousLogout;
        BotTrainingPlan.loadConfig = previousLoad;
    }

    private BotEntry rosterBotAtCap(boolean selfOwned) {
        BotTrainingPlan.saveConfig = (id, config) -> saved.add(config);
        BotTrainingPlan.logout = loggedOut::add;
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(266);
        when(bot.getName()).thenReturn("SipsBuddy13");
        String role = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("Quest farmer A10".getBytes(StandardCharsets.UTF_8));
        BotEntry entry = new BotEntry(bot, selfOwned ? bot : mock(Character.class), null);
        entry.personality = BotPersonality.parse("v=1;trainingLevel=43;rosterRole=" + role + ";rosterActive=true");
        return entry;
    }

    @Test
    void parksASelfOwnedRosterBotAfterTenIdleMinutesAtItsCap() {
        BotEntry entry = rosterBotAtCap(true);
        long t0 = 1_000_000L;

        BotTrainingPlan.parkIfIdleAtCap(entry, entry.bot, true, t0);
        BotTrainingPlan.parkIfIdleAtCap(entry, entry.bot, true, t0 + BotTrainingPlan.PARK_AFTER_IDLE_MS - 1);
        assertTrue(saved.isEmpty(), "still inside the idle window");

        BotTrainingPlan.parkIfIdleAtCap(entry, entry.bot, true, t0 + BotTrainingPlan.PARK_AFTER_IDLE_MS);
        assertEquals(1, saved.size());
        assertTrue(saved.get(0).contains("rosterActive=false"));
        assertFalse(entry.personality.rosterActive());
        assertEquals(List.of(266), loggedOut);

        // Already parked: nothing more happens.
        BotTrainingPlan.parkIfIdleAtCap(entry, entry.bot, true, t0 + 3 * BotTrainingPlan.PARK_AFTER_IDLE_MS);
        assertEquals(1, saved.size());
    }

    @Test
    void leavingTheCapStateResetsTheIdleClock() {
        BotEntry entry = rosterBotAtCap(true);
        BotTrainingPlan.parkIfIdleAtCap(entry, entry.bot, true, 0L + 1);
        BotTrainingPlan.parkIfIdleAtCap(entry, entry.bot, false, 5 * 60_000L);
        BotTrainingPlan.parkIfIdleAtCap(entry, entry.bot, true, 9 * 60_000L);
        BotTrainingPlan.parkIfIdleAtCap(entry, entry.bot, true, 12 * 60_000L);
        assertTrue(saved.isEmpty(), "the clock restarted at minute 9");
    }

    @Test
    void neverParksABotSomeoneElseControls() {
        BotEntry entry = rosterBotAtCap(false);
        BotTrainingPlan.parkIfIdleAtCap(entry, entry.bot, true, 1L);
        BotTrainingPlan.parkIfIdleAtCap(entry, entry.bot, true, 1L + 2 * BotTrainingPlan.PARK_AFTER_IDLE_MS);
        assertTrue(saved.isEmpty());
        assertTrue(loggedOut.isEmpty());
    }

    @Test
    void unparkingRestoresTheRosterPlaceAParkTookAway() {
        BotEntry entry = rosterBotAtCap(true);
        assertTrue(BotTrainingPlan.park(entry, entry.bot, "parked by the navi responder"));
        String parked = saved.get(0);
        BotTrainingPlan.loadConfig = id -> id == 266 ? parked : null;

        assertTrue(BotTrainingPlan.unpark(266, entry));
        assertEquals(2, saved.size());
        assertTrue(saved.get(1).contains("rosterActive=true"));
        assertEquals(entry.personality.rosterRole(), BotPersonality.parse(saved.get(1)).rosterRole(), "keeps the role");
        assertTrue(entry.personality.rosterActive(), "the live entry can be parked again");
        assertTrue(BotTrainingPlan.canPark(entry));

        // Never parked: nothing to save. No stored config: nothing to unpark.
        BotTrainingPlan.loadConfig = id -> saved.get(1);
        assertTrue(BotTrainingPlan.unpark(266, null));
        assertEquals(2, saved.size());
        BotTrainingPlan.loadConfig = id -> null;
        assertFalse(BotTrainingPlan.unpark(266, null));
    }
}
