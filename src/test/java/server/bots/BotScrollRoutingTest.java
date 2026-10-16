package server.bots;

import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotScrollRoutingTest {
    // Scroll 204xxyy applies to equip kind 1xx (the category rule): dagger 133, gloves 108, helmet 100.
    private static final int DAGGER_ATT = 2043300, GLOVE_ATT = 2040804, HELMET_DEX = 2040002;
    private static final int DAGGER = 1332000, GLOVES = 1082002, HELMET = 1002000, WAND = 1372000;

    @BeforeEach
    void hermeticItemData() {
        BotOfferManager.scrollRules = new BotOfferManager.ScrollRules() {
            @Override public Map<String, Integer> stats(int scrollId) {
                return scrollId == HELMET_DEX ? Map.of("DEX", 1) : Map.of("PAD", 1);
            }
            @Override public boolean isCash(int itemId) { return false; }
            @Override public boolean applicable(int scrollId, int equipId) {
                return BotScrollRouting.kindOf(scrollId) == equipId / 10000;
            }
            @Override public boolean offerable(int scrollId) { return true; }
        };
    }

    @AfterEach
    void restore() {
        BotOfferManager.scrollRules = null;
    }

    private static BotStorageMuleManager.Member member(int id, int job, Set<Integer> worn) {
        return new BotStorageMuleManager.Member(id, job, 40, Map.of(), Map.of(), Map.of(), "m" + id, worn, Map.of());
    }

    private static BotStorageMuleManager.Directory fleet(BotStorageMuleManager.Member... members) {
        Map<Integer, Integer> ownerOf = new java.util.HashMap<>();
        for (BotStorageMuleManager.Member m : members) {
            ownerOf.put(m.charId(), 9);
        }
        ownerOf.put(70, 9);
        ownerOf.put(71, 9);
        ownerOf.put(72, 9);
        return new BotStorageMuleManager.Directory(ownerOf,
                Map.of(9, Map.of("thief weapons", 70, "gloves", 71, "warrior armor", 72)),
                Map.of(70, "thief weapons", 71, "gloves", 72, "warrior armor"),
                Map.of(9, List.of(members)), 0L);
    }

    @Test
    void aScrollGoesToTheBinOfTheGearKindItIsFor() {
        var mage = member(1, 222, Set.of(WAND));
        var thief = member(2, 410, Set.of(DAGGER, GLOVES));
        var warrior = member(3, 110, Set.of(HELMET, GLOVES));
        var dir = fleet(mage, thief, warrior);
        assertEquals("Thief weapons", BotScrollRouting.binRole(dir, 1, DAGGER_ATT));
        assertEquals("Gloves", BotScrollRouting.binRole(dir, 1, GLOVE_ATT));       // by kind, any class
        assertEquals("Warrior armor", BotScrollRouting.binRole(dir, 1, HELMET_DEX)); // armor by its takers' class
        assertNull(BotScrollRouting.binRole(fleet(mage), 1, DAGGER_ATT));             // nobody can use it: it stays
    }

    @Test
    void theHolderDropsItWithTheBinThatTakesIt() {
        var dir = fleet(member(1, 222, Set.of(WAND)), member(2, 410, Set.of(DAGGER)));
        Item scroll = mock(Item.class);
        when(scroll.getItemId()).thenReturn(DAGGER_ATT);
        when(scroll.getQuantity()).thenReturn((short) 9);
        assertEquals(Map.of(70, List.of(scroll)), BotStorageMuleManager.planBins(dir, 1, List.of(scroll)));
    }

    @Test
    void aTakerFetchesOnlyAsManyAsItHasOpenSlotsForBestFirst() {
        var stock = List.of(new BotScrollRouting.Stock(2040805, 108, 10, 0.6),  // a weaker glove scroll
                new BotScrollRouting.Stock(GLOVE_ATT, 108, 3, 1.2),
                new BotScrollRouting.Stock(DAGGER_ATT, 133, 9, 0.5));
        // Five open glove slots, nothing on the dagger (or the scrolls in the bag already cover it).
        assertEquals(Map.of(GLOVE_ATT, 3, 2040805, 2),
                BotScrollRouting.wanted(stock, Map.of(108, 5, 133, 0)));
        assertTrue(BotScrollRouting.wanted(stock, Map.of()).isEmpty());
    }
}
