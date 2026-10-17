package server.bots;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotScrollHuntTest {
    private static final int WAND_MATK = 2043700, CAPE_INT = 2041000, BW_ATT = 2043200, WHITE = 2340000;
    private static final int WAND = 1372000, CAPE = 1102000;

    private static final BotOfferManager.ScrollRules RULES = new BotOfferManager.ScrollRules() {
        @Override public Map<String, Integer> stats(int scrollId) {
            return switch (scrollId) {
                case WAND_MATK -> Map.of("MAD", 2, "success", 60);
                case CAPE_INT -> Map.of("INT", 2, "success", 60);
                case BW_ATT -> Map.of("PAD", 2, "success", 60);
                default -> Map.of();
            };
        }
        @Override public boolean isCash(int itemId) { return false; }
        @Override public boolean applicable(int scrollId, int equipId) {
            return (scrollId / 100) % 100 == (equipId / 10000) % 100; // the category rule
        }
        @Override public boolean offerable(int scrollId) { return scrollId != WHITE; }
    };

    private static BotStorageMuleManager.Member member(int id, int job, Set<Integer> worn, Map<Integer, Integer> scrolls) {
        return new BotStorageMuleManager.Member(id, job, 70, Map.of(), Map.of(), Map.of(), "m" + id, worn, scrolls);
    }

    @Test
    void theHuntIsTheScrollsTheFleetCouldUseBeyondTheCopiesItHolds() {
        var farmer = member(1, 222, Set.of(WAND, CAPE), Map.of());
        var support = member(2, 231, Set.of(WAND, CAPE), Map.of(WAND_MATK, 1));
        var holder = member(3, 110, Set.of(), Map.of(CAPE_INT, 103)); // can't use them: stranded in the fleet
        var hunter = member(4, 110, Set.of(WAND), Map.of());           // capped; never its own taker
        var mule = member(5, 0, Set.of(WAND, CAPE), Map.of());         // a beginner scrolls nothing
        var dir = new BotStorageMuleManager.Directory(Map.of(1, 9, 2, 9, 3, 9, 4, 9, 5, 9), Map.of(), Map.of(),
                Map.of(9, List.of(farmer, support, holder, hunter, mule)), 0L);

        Map<Integer, Double> want = BotScrollHunt.wanted(dir, 4, RULES, Set.of(WAND_MATK, CAPE_INT, BW_ATT, WHITE));

        // Two mages could use a wand scroll and the fleet holds one: one short. 103 cape scrolls cover both mages.
        // Nobody uses a blunt weapon scroll; the white scroll is never routed.
        assertEquals(Set.of(WAND_MATK), want.keySet());
        assertTrue(want.get(WAND_MATK) > 0);
        assertEquals(Map.of(), BotScrollHunt.wanted(dir, 77, RULES, Set.of(WAND_MATK))); // a bot with no fleet
    }
}
