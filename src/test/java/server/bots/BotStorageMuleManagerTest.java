package server.bots;

import client.Character;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.MapItem;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotStorageMuleManagerTest {
    @AfterEach
    void clearDuties() {
        BotStorageMuleManager.DUTIES.clear();
    }

    @Test
    void eachMakerMaterialGoesToTheBinItsRosterRoleNames() {
        assertEquals("Power crystals", BotStorageMuleManager.binRole(4004000)); // Power Crystal Ore
        assertEquals("Power crystals", BotStorageMuleManager.binRole(4005003)); // LUK Crystal
        assertEquals("Dark crystals", BotStorageMuleManager.binRole(4004004));
        assertEquals("Dark crystals", BotStorageMuleManager.binRole(4005004));
        assertEquals("Diamond, sapphire, aquamarine", BotStorageMuleManager.binRole(4020007)); // Diamond Ore
        assertEquals("Emerald, topaz, opal", BotStorageMuleManager.binRole(4021003)); // Emerald
        assertEquals("Garnet, amethyst, black crystal", BotStorageMuleManager.binRole(4020008)); // Black Crystal Ore
        assertEquals("Mithril, adamantium, steel", BotStorageMuleManager.binRole(4011002)); // Mithril Plate
        assertEquals("Iron and bronze", BotStorageMuleManager.binRole(4010000)); // Bronze Ore
        assertEquals("Iron and bronze", BotStorageMuleManager.binRole(4010005)); // Orihalcon Ore, no own bin
        assertEquals("Processed materials", BotStorageMuleManager.binRole(4007000)); // Magic Powder
        assertEquals("Processed materials", BotStorageMuleManager.binRole(4011007)); // Moon Rock
        assertNull(BotStorageMuleManager.binRole(4000000)); // Snail Shell: a mob drop, it sells
    }

    @Test
    void aShelfSplitsByTheOwnersMulesAndLeavesBinsWithoutAMuleOnTheShelf() {
        int owner = 1;
        int farmer = 10;
        var dir = new BotStorageMuleManager.Directory(
                Map.of(farmer, owner, 61, owner, 93, owner),
                Map.of(owner, Map.of("power crystals", 61, "processed materials", 93)),
                Map.of(61, "power crystals", 93, "processed materials"), 0L);
        Item ore = new Item(4004000, (short) 1, (short) 40);
        Item powder = new Item(4007001, (short) 2, (short) 90);
        Item garnet = new Item(4020000, (short) 3, (short) 12); // no mule holds the garnet bin
        Item moreOre = new Item(4004001, (short) 4, (short) 7);

        Map<Integer, List<Item>> plan = BotStorageMuleManager.planBins(dir, farmer, List.of(ore, powder, garnet, moreOre));

        assertEquals(List.of(61, 93), List.copyOf(plan.keySet())); // bin order
        assertEquals(List.of(ore, moreOre), plan.get(61));
        assertEquals(List.of(powder), plan.get(93));
        assertTrue(BotStorageMuleManager.planBins(dir, 11, List.of(ore)).isEmpty()); // another owner's farmer
        assertTrue(BotStorageMuleManager.planBins(null, farmer, List.of(ore)).isEmpty()); // directory loading
    }

    @Test
    void theMuleOnDutyTakesItsPartnersDropAtOnceAndNoOneElsesEarly() {
        Character mule = mock(Character.class);
        when(mule.getId()).thenReturn(61);
        Character stranger = mock(Character.class);
        when(stranger.getId()).thenReturn(62);
        BotStorageMuleManager.DUTIES.put(61, new BotStorageMuleManager.Duty(10, System.currentTimeMillis()));

        MapItem handoff = drop(10, true);
        assertTrue(BotStorageMuleManager.isPartnerDrop(mule, handoff));
        assertEquals(BotLootEligibility.MIN_TARGET_LOOT_AGE_MS,
                BotLootEligibility.requiredTargetLootAgeMs(mule, handoff));
        assertFalse(BotStorageMuleManager.isPartnerDrop(mule, drop(11, true))); // someone else's farmer
        assertFalse(BotStorageMuleManager.isPartnerDrop(mule, drop(10, false))); // a mob drop
        assertFalse(BotStorageMuleManager.isPartnerDrop(stranger, handoff)); // not on duty
    }

    @Test
    void theFreeMarketIsEnteredByEachTownsPortalAndLeftOnlyToTheSavedTown() {
        assertEquals("market00", BotWorldGraph.scriptedEntrancePortal(100000100, BotWorldGraph.FREE_MARKET));
        assertEquals("st00", BotWorldGraph.scriptedEntrancePortal(130000200, BotWorldGraph.FREE_MARKET));
        assertEquals("market0", BotWorldGraph.scriptedEntrancePortal(261000000, BotWorldGraph.FREE_MARKET));
        assertEquals("out00", BotWorldGraph.scriptedEntrancePortal(BotWorldGraph.FREE_MARKET, 100000100));
        assertNull(BotWorldGraph.scriptedEntrancePortal(BotWorldGraph.FREE_MARKET, 910000001)); // a room

        BotWorldGraph.Index graph = BotWorldGraph.indexOf(Map.of(
                BotWorldGraph.FREE_MARKET, new int[0], 100000100, new int[0], 200000000, new int[0]));
        var outside = new BotWorldGraph.RouteOptions(false, 0, false, false, Integer.MAX_VALUE, -1, -1);
        var cameFromHenesys = new BotWorldGraph.RouteOptions(false, 0, false, false, Integer.MAX_VALUE, -1, 100000100);
        assertNull(BotWorldGraph.route(graph, BotWorldGraph.FREE_MARKET, 100000100, 4, outside));
        assertEquals(List.of(100000100), BotWorldGraph.route(graph, BotWorldGraph.FREE_MARKET, 100000100, 4, cameFromHenesys));
        assertNull(BotWorldGraph.route(graph, BotWorldGraph.FREE_MARKET, 200000000, 4, cameFromHenesys)); // no shortcut
    }

    private static MapItem drop(int ownerId, boolean playerDrop) {
        MapItem drop = mock(MapItem.class);
        when(drop.getOwnerId()).thenReturn(ownerId);
        when(drop.isPlayerDrop()).thenReturn(playerDrop);
        return drop;
    }
}
