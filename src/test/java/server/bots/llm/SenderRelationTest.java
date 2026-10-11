package server.bots.llm;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.bots.BotEntry;
import server.bots.BotOwnershipService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class SenderRelationTest {

    private static Character character(int id) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        return c;
    }

    /** A self-owned roster bot: its live owner is itself. */
    private static BotEntry selfOwned(Character bot) {
        BotEntry entry = mock(BotEntry.class);
        when(entry.getBot()).thenReturn(bot);
        when(entry.getOwner()).thenReturn(bot);
        return entry;
    }

    @Test
    void registeredOwnerOfASelfOwnedBotIsTheOwner() {
        Character bot = character(254);
        Character sipsaeki = character(252);
        BotOwnershipService service = mock(BotOwnershipService.class);
        when(service.getRegisteredOwnerId(254)).thenReturn(252);
        try (MockedStatic<BotOwnershipService> s = mockStatic(BotOwnershipService.class)) {
            s.when(BotOwnershipService::getInstance).thenReturn(service);
            assertEquals(SenderRelation.OWNER, SenderRelation.resolve(selfOwned(bot), sipsaeki));
            assertEquals(SenderRelation.STRANGER, SenderRelation.resolve(selfOwned(bot), character(999)));
        }
    }

    @Test
    void ownershipLookupFailureFallsBackToStranger() {
        Character bot = character(254);
        BotOwnershipService service = mock(BotOwnershipService.class);
        when(service.getRegisteredOwnerId(254)).thenThrow(new IllegalStateException("no database"));
        try (MockedStatic<BotOwnershipService> s = mockStatic(BotOwnershipService.class)) {
            s.when(BotOwnershipService::getInstance).thenReturn(service);
            assertEquals(SenderRelation.STRANGER, SenderRelation.resolve(selfOwned(bot), character(252)));
        }
    }
}
