package server.bots;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotScrollHuntTest {
    private static final int WAND_MATK = 2043700, CAPE_INT = 2041000, BW_ATT = 2043200, BW_ATT_60 = 2043201, WHITE = 2340000;
    private static final int BLUNT = 1322005;
    private static final int WAND = 1372000, CAPE = 1102000;

    private static final BotOfferManager.ScrollRules RULES = new BotOfferManager.ScrollRules() {
        @Override public Map<String, Integer> stats(int scrollId) {
            return switch (scrollId) {
                case WAND_MATK -> Map.of("MAD", 2, "success", 60);
                case CAPE_INT -> Map.of("INT", 2, "success", 60);
                case BW_ATT -> Map.of("PAD", 1, "success", 100);
                case BW_ATT_60 -> Map.of("PAD", 2, "success", 60);
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

        Map<Integer, Double> want = BotScrollHunt.wanted(dir, 4, RULES, Set.of(WAND_MATK, CAPE_INT, BW_ATT, WHITE), Map.of());

        // Two mages could use a wand scroll and the fleet holds one: one short. 103 cape scrolls cover both mages.
        // Nobody uses a blunt weapon scroll; the white scroll is never routed.
        assertEquals(Set.of(WAND_MATK), want.keySet());
        assertTrue(want.get(WAND_MATK) > 0);
        assertEquals(Map.of(), BotScrollHunt.wanted(dir, 77, RULES, Set.of(WAND_MATK), Map.of())); // a bot with no fleet
    }

    @Test
    void scrollsForTheSameSlotsAndStatsAreSubstitutes() {
        // Live: SipsBuddy26 hunted a 60% blunt weapon attack scroll while 150 of the 100% kind sat in the fleet.
        var fighter1 = member(1, 110, Set.of(BLUNT), Map.of());
        var fighter2 = member(2, 110, Set.of(BLUNT), Map.of());
        var holder = member(3, 222, Set.of(), Map.of(BW_ATT, 150));
        var dir = new BotStorageMuleManager.Directory(Map.of(1, 9, 2, 9, 3, 9, 4, 9), Map.of(), Map.of(),
                Map.of(9, List.of(fighter1, fighter2, holder)), 0L);
        assertEquals(Map.of(), BotScrollHunt.wanted(dir, 4, RULES, Set.of(BW_ATT, BW_ATT_60), Map.of()));
        assertEquals(BotScrollHunt.substituteKind(RULES, BW_ATT), BotScrollHunt.substituteKind(RULES, BW_ATT_60));
    }

    private static BotEntry hunting(int id, int scrollId) {
        client.Character bot = org.mockito.Mockito.mock(client.Character.class);
        org.mockito.Mockito.when(bot.getId()).thenReturn(id);
        BotEntry e = new BotEntry(bot, null, null);
        e.scrollHunt = true;
        e.huntMapId = 104000100;
        e.huntItemId = scrollId;
        return e;
    }

    @Test
    void otherHuntersOnAScrollClaimItsShortfall() {
        // Live 2026-11-03: six capped hunters all farmed one helmet DEF scroll while the fleet lacked others too.
        var mage1 = member(1, 222, Set.of(WAND, CAPE), Map.of());
        var mage2 = member(2, 231, Set.of(WAND, CAPE), Map.of());
        var hunters = List.of(member(4, 110, Set.of(), Map.of()), member(5, 110, Set.of(), Map.of()),
                member(6, 110, Set.of(), Map.of()), member(7, 110, Set.of(), Map.of()));
        var stranger = member(8, 110, Set.of(), Map.of()); // another owner's hunter claims nothing here
        var members = new java.util.ArrayList<>(List.of(mage1, mage2));
        members.addAll(hunters);
        var dir = new BotStorageMuleManager.Directory(Map.of(1, 9, 2, 9, 4, 9, 5, 9, 6, 9, 7, 9, 8, 3), Map.of(),
                Map.of(), Map.of(9, members, 3, List.of(stranger)), 0L);
        Set<Integer> candidates = Set.of(WAND_MATK, CAPE_INT);
        assertEquals(candidates, BotScrollHunt.wanted(dir, 4, RULES, candidates, Map.of()).keySet(), "alone: both");

        // Hunters 5 and 6 are on the wand scroll (two short), 7 is idle, 8 is not this fleet's, 4 is the asker.
        BotEntry idle = hunting(7, WAND_MATK);
        idle.huntMapId = -1;
        var claims = BotScrollHunt.claimedByKind(dir, 4, RULES,
                List.of(hunting(4, WAND_MATK), hunting(5, WAND_MATK), hunting(6, WAND_MATK), idle, hunting(8, WAND_MATK)));
        assertEquals(Map.of(BotScrollHunt.substituteKind(RULES, WAND_MATK), 2), claims);
        assertEquals(Set.of(CAPE_INT), BotScrollHunt.wanted(dir, 4, RULES, candidates, claims).keySet(),
                "the wand shortfall is covered, so the fourth hunter takes the cape scroll");
    }
}
