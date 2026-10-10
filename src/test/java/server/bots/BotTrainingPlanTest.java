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
    private final List<String> saved = new ArrayList<>();
    private final List<Integer> loggedOut = new ArrayList<>();

    @AfterEach
    void restore() {
        BotTrainingPlan.saveConfig = previousSave;
        BotTrainingPlan.logout = previousLogout;
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
}
