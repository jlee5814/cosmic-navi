package server.bots;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotFleetScrollMatchTest {
    private static final int BW_ATT = 2043200, WAND_MATK = 2043700, WHITE = 2340000; // scroll categories 32, 37
    private static final int BLUNT = 1322005, WAND = 1372000, CASH_HAT = 1002186;

    @BeforeEach
    void hermeticItemData() {
        BotOfferManager.scrollRules = new BotOfferManager.ScrollRules() {
            @Override public Map<String, Integer> stats(int scrollId) {
                return scrollId == BW_ATT ? Map.of("PAD", 1) : scrollId == WAND_MATK ? Map.of("MAD", 1) : Map.of();
            }
            @Override public boolean isCash(int itemId) { return itemId == CASH_HAT; }
            @Override public boolean applicable(int scrollId, int equipId) {
                return (scrollId / 100) % 100 == (equipId / 10000) % 100; // the category rule
            }
            @Override public boolean offerable(int scrollId) { return scrollId != WHITE; }
        };
    }

    @AfterEach
    void restore() {
        BotOfferManager.scrollRules = null;
    }

    private static BotStorageMuleManager.Member member(int id, String name, int job, Set<Integer> worn,
                                                       Map<Integer, Integer> scrolls) {
        return new BotStorageMuleManager.Member(id, job, 50, Map.of(), Map.of(), Map.of(), name, worn, scrolls);
    }

    @Test
    void aScrollItsHolderCantUseIsMatchedToTheFleetMembersWhoCan() {
        var mage = member(1, "Mage", 222, Set.of(WAND, CASH_HAT), Map.of(BW_ATT, 44, WAND_MATK, 3, WHITE, 7));
        var cleric = member(2, "Cleric", 230, Set.of(BLUNT), Map.of());   // a mage too: wants MATK, not PAD
        var fighter = member(3, "Fighter", 110, Set.of(BLUNT), Map.of());
        var mule = member(4, "Mule", 0, Set.of(BLUNT), Map.of());         // a beginner scrolls nothing
        var otherOwnersFighter = member(5, "Stranger", 110, Set.of(BLUNT), Map.of());
        var dir = new BotStorageMuleManager.Directory(Map.of(1, 9, 2, 9, 3, 9, 4, 9, 5, 8), Map.of(), Map.of(),
                Map.of(9, List.of(mage, cleric, fighter, mule), 8, List.of(otherOwnersFighter)), 0L);

        List<BotOfferManager.ScrollMatch> matches = BotOfferManager.fleetScrollMatches(dir);
        assertEquals(1, matches.size()); // its own wand scroll stays; the white scroll is never routed
        BotOfferManager.ScrollMatch m = matches.get(0);
        assertEquals(BW_ATT, m.scrollId());
        assertEquals(44, m.qty());
        assertEquals(List.of(fighter), m.takers());
    }

    @Test
    void aScrollNobodyInTheFleetCanUseIsStillListed() {
        var mage = member(1, "Mage", 222, Set.of(WAND), Map.of(BW_ATT, 2));
        var dir = new BotStorageMuleManager.Directory(Map.of(1, 9), Map.of(), Map.of(), Map.of(9, List.of(mage)), 0L);
        List<BotOfferManager.ScrollMatch> matches = BotOfferManager.fleetScrollMatches(dir);
        assertEquals(1, matches.size());
        assertTrue(matches.get(0).takers().isEmpty());
        assertTrue(BotOfferManager.fleetScrollMatches(null).isEmpty()); // snapshot loading
    }
}
