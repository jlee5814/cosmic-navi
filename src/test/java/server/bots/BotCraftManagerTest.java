package server.bots;

import client.Character;
import client.Client;
import client.inventory.Equip;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.AbstractPlayerInteraction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotCraftManagerTest {
    // Real recipes from npc-recipes.csv (Perion: 1022003 refines, 1022004 makes gloves).
    private static final int LEATHER = 4000021, BRONZE_ORE = 4010000, STEEL_ORE = 4010001;
    private static final int BRONZE_PLATE = 4011000, STEEL_PLATE = 4011001, SCREW = 4003000;
    private static final int JUNO = 1082003, STEEL_FINGERLESS = 1082007;
    private static final int WORK_GLOVES = 1082002, WORN_GLOVES = 1082145; // Work Gloves: 15 Leather at 1052002

    private BotCraftManager.Lookups prev;
    private final Map<Integer, Map<String, Integer>> stats = new HashMap<>();
    private final Map<Integer, Double> potential = new HashMap<>(); // base value plus scroll headroom
    private final Map<Integer, Double> value = new HashMap<>();     // base value alone
    private Equip worn;
    private double obtainCost = 1_000_000;

    private static Equip equip(int id) {
        Equip e = mock(Equip.class);
        when(e.getItemId()).thenReturn(id);
        return e;
    }

    @BeforeEach
    void hermeticLookups() {
        prev = BotCraftManager.lookups;
        stats.put(JUNO, Map.of("reqLevel", 15));
        potential.put(JUNO, 10.0);
        value.put(JUNO, 10.0);
        BotCraftManager.lookups = new BotCraftManager.Lookups() {
            @Override public Map<String, Integer> stats(int itemId) { return stats.get(itemId); }
            @Override public Equip clean(int itemId) { return stats.containsKey(itemId) ? equip(itemId) : null; }
            @Override public double potential(Character bot, Equip e) { return potential.getOrDefault(e.getItemId(), 0.0); }
            @Override public double value(Character bot, Equip e) { return value.getOrDefault(e.getItemId(), 0.0); }
            @Override public Equip worn(Character bot, int itemId) { return worn; }
            @Override public boolean wearable(Character bot, Equip e) { return true; }
            @Override public boolean preferredWeapon(Character bot, Equip e) { return true; }
            @Override public int sellPrice(int itemId, int qty) { return qty; }
            @Override public double obtainCost(Character bot, int itemId) { return obtainCost; }
        };
    }

    @AfterEach
    void restore() {
        BotCraftManager.lookups = prev;
    }

    private static BotCraftManager.Recipe recipe(int npc, int out) {
        return BotCraftManager.RECIPES.stream().filter(r -> r.npcId() == npc && r.outputId() == out)
                .findFirst().orElseThrow();
    }

    private static Character bot(int level) {
        Character bot = mock(Character.class);
        when(bot.getLevel()).thenReturn(level);
        return bot;
    }

    @Test
    void recipesLoadWithYields() {
        assertEquals(15, recipe(1022004, SCREW).yield());
        assertEquals(300, recipe(1022003, STEEL_PLATE).fee());
        assertTrue(recipe(1022004, JUNO).gear());
        assertFalse(recipe(1022003, STEEL_PLATE).gear());
        for (BotCraftManager.Recipe r : BotCraftManager.RECIPES) { // no script placeholders (1052003 pads with 1)
            assertTrue(r.outputId() >= 1000000, r.toString());
            r.mats().forEach(m -> assertTrue(m.itemId() >= 1000000 && m.qty() > 0, r.toString()));
        }
    }

    @Test
    void craftCostIsTheFeePlusTheInputsOverTheYield() {
        // Work Gloves: 1,000 meso and 15 Leather. Screws: two plates for 15.
        assertEquals(1000 + 15 * 10.0, BotCraftManager.craftCostMeso(1082002, id -> id == LEATHER ? 10.0 : 1e9));
        double screw = BotCraftManager.craftCostMeso(SCREW, id -> 15.0);
        assertTrue(screw > 0 && screw <= 2.0, String.valueOf(screw));
        assertTrue(Double.isInfinite(BotCraftManager.craftCostMeso(LEATHER, id -> 1.0))); // a drop, not a craft
        assertTrue(Double.isInfinite(BotCraftManager.craftCostMeso(1082002, id -> Double.POSITIVE_INFINITY)));
        assertEquals(1.0, new BotScrollManager.Acquisition("shop", 3_600_000).hours(), 1e-9); // effort anchor
    }

    @Test
    void craftingPartsAreTheNonMakerEtcInputsOfGear() {
        Map<Integer, Integer> parts = BotCraftManager.craftingPartsMaxPerRecipe();
        assertEquals(100, parts.get(LEATHER));
        assertEquals(65, parts.get(SCREW));
        assertFalse(parts.containsKey(STEEL_PLATE)); // a maker material rides its own shelf rule
    }

    @Test
    void planRefinesOreIntoThePlateTheGoalNeeds() {
        Map<Integer, Integer> have = Map.of(LEATHER, 15, STEEL_ORE, 10);
        BotCraftManager.Plan p = BotCraftManager.plan(bot(20), recipe(1022004, JUNO), have, 1);
        assertNotNull(p);
        assertEquals(2, p.steps().size());
        assertEquals(STEEL_PLATE, p.steps().get(0).recipe().outputId());
        assertEquals(1, p.steps().get(0).times());
        assertEquals(JUNO, p.steps().get(1).recipe().outputId());
        assertEquals(1000 + p.steps().get(0).recipe().fee(), p.fees());
        assertEquals(25, p.inputValue()); // 15 leather + 10 ore at 1 meso each
        assertEquals(Map.of(LEATHER, 15, STEEL_ORE, 10), p.used()); // the plate it makes isn't drawn from holdings
        // Holding 5 leather, the bins must bring the other 10 and all the ore.
        assertEquals(Map.of(LEATHER, 10, STEEL_ORE, 10), BotCraftManager.shortfall(p.used(), Map.of(LEATHER, 5)));
        assertTrue(BotCraftManager.shortfall(p.used(), have).isEmpty());
    }

    @Test
    void planMakesScrewsFromPlatesAndKeepsEnoughPlatesForTheGoal() {
        // The gloves need 3 bronze, 2 steel and 15 screws; one screw craft (1 bronze + 1 steel) yields 15.
        Map<Integer, Integer> have = Map.of(BRONZE_PLATE, 4, STEEL_PLATE, 3);
        BotCraftManager.Plan p = BotCraftManager.plan(bot(30), recipe(1022004, STEEL_FINGERLESS), have, 1);
        assertNotNull(p);
        assertEquals(List.of(SCREW, STEEL_FINGERLESS), p.steps().stream().map(s -> s.recipe().outputId()).toList());
        assertEquals(1022004, p.steps().get(0).recipe().npcId()); // the goal's own NPC makes the screws
        assertNull(BotCraftManager.plan(bot(30), recipe(1022004, STEEL_FINGERLESS),
                Map.of(BRONZE_PLATE, 3, STEEL_PLATE, 3), 1)); // a bronze plate short once screws take one
    }

    @Test
    void planRefinesPlatesForScrewsWhenOnlyOreIsHeld() {
        Map<Integer, Integer> have = Map.of(BRONZE_ORE, 40, STEEL_ORE, 30);
        BotCraftManager.Plan p = BotCraftManager.plan(bot(30), recipe(1022004, STEEL_FINGERLESS), have, 1);
        assertNotNull(p);
        List<Integer> outs = p.steps().stream().map(s -> s.recipe().outputId()).toList();
        assertEquals(STEEL_FINGERLESS, outs.get(outs.size() - 1));
        assertTrue(outs.indexOf(SCREW) > outs.indexOf(BRONZE_PLATE) && outs.indexOf(SCREW) > outs.indexOf(STEEL_PLATE));
        int bronze = p.steps().stream().filter(s -> s.recipe().outputId() == BRONZE_PLATE)
                .mapToInt(BotCraftManager.Step::times).sum();
        int steel = p.steps().stream().filter(s -> s.recipe().outputId() == STEEL_PLATE)
                .mapToInt(BotCraftManager.Step::times).sum();
        assertEquals(4, bronze); // 3 for the gloves + 1 for the screws, 10 ore each
        assertEquals(3, steel);
        assertEquals(Map.of(BRONZE_ORE, 40, STEEL_ORE, 30), p.used());
    }

    @Test
    void bestPlanPicksAWearableUpgradeTheBagCovers() {
        Map<Integer, Integer> have = Map.of(LEATHER, 15, STEEL_ORE, 10);
        BotCraftManager.Plan p = BotCraftManager.bestPlan(bot(20), have, 10_000, false, null);
        assertNotNull(p);
        assertEquals(JUNO, p.goal().outputId());
        assertFalse(p.scrollBase()); // better as it comes from the NPC
    }

    @Test
    void bestPlanSkipsWhatItCantUseOrAfford() {
        Map<Integer, Integer> have = Map.of(LEATHER, 15, STEEL_ORE, 10);
        assertNull(BotCraftManager.bestPlan(bot(14), have, 10_000, true, null), "level too low");
        assertNull(BotCraftManager.bestPlan(bot(20), have, 2_000, true, null), "fees take more than half the meso");
        assertNull(BotCraftManager.bestPlan(bot(20), Map.of(LEATHER, 14, STEEL_ORE, 10), 10_000, true, null), "short");
        worn = equip(WORN_GLOVES);
        potential.put(WORN_GLOVES, 12.0);
        assertNull(BotCraftManager.bestPlan(bot(60), have, 10_000, true, null), "what it wears scores higher");
        worn = null;
        obtainCost = 1_300;
        assertNull(BotCraftManager.bestPlan(bot(20), have, 10_000, true, null), "cheaper to get it another way");
    }

    @Test
    void wantsListTheRawMaterialsAnUpgradeLacks() {
        // An empty bag: Juno needs 15 Leather and a Steel Plate, which it would refine from 10 Steel Ore.
        List<BotCraftManager.Want> wants = BotCraftManager.wants(null, bot(20));
        assertEquals(1, wants.size());
        assertEquals(JUNO, wants.get(0).recipe().outputId());
        assertEquals(10.0, wants.get(0).gain());
        assertEquals(Map.of(LEATHER, 15, STEEL_ORE, 10), wants.get(0).missing());
        assertTrue(BotCraftManager.wants(null, bot(14)).isEmpty()); // above its level: not a want
    }

    @Test
    void gearItCantWearIsNoWantEvenWhenShort() {
        BotCraftManager.Lookups base = BotCraftManager.lookups;
        BotCraftManager.lookups = new BotCraftManager.Lookups() {
            @Override public Map<String, Integer> stats(int itemId) { return base.stats(itemId); }
            @Override public Equip clean(int itemId) { return base.clean(itemId); }
            @Override public double potential(Character bot, Equip e) { return base.potential(bot, e); }
            @Override public double value(Character bot, Equip e) { return base.value(bot, e); }
            @Override public Equip worn(Character bot, int itemId) { return base.worn(bot, itemId); }
            @Override public boolean wearable(Character bot, Equip e) { return false; } // another class's gear
            @Override public boolean preferredWeapon(Character bot, Equip e) { return true; }
            @Override public int sellPrice(int itemId, int qty) { return base.sellPrice(itemId, qty); }
            @Override public double obtainCost(Character bot, int itemId) { return base.obtainCost(bot, itemId); }
        };
        assertTrue(BotCraftManager.wants(null, bot(20)).isEmpty());
        Map<String, Integer> why = new HashMap<>();
        BotCraftManager.bestPlan(bot(20), Map.of(), 10_000, true, (r, reason, gain) -> why.merge(reason, 1, Integer::sum));
        assertEquals(1, why.get("can't wear it yet")); // Juno, an empty bag: the wear check comes first
        assertNull(why.get(BotCraftManager.SHORT));
    }

    @Test
    void aCleanBaseThatWinsOnceScrolledIsCraftedOnlyByABotThatScrolls() {
        // Its gloves beat clean Work Gloves as they are, but have no slots left; five open slots win once scrolled.
        worn = equip(WORN_GLOVES);
        value.put(WORN_GLOVES, 3.0);
        potential.put(WORN_GLOVES, 3.0);
        stats.put(WORK_GLOVES, Map.of("reqLevel", 10));
        value.put(WORK_GLOVES, 1.0);
        potential.put(WORK_GLOVES, 6.0);
        potential.put(JUNO, 2.0); // Juno loses either way
        Map<Integer, Integer> have = Map.of(LEATHER, 15);
        assertNull(BotCraftManager.bestPlan(bot(40), have, 10_000, false, null), "it never scrolls its gear");
        BotCraftManager.Plan p = BotCraftManager.bestPlan(bot(40), have, 10_000, true, null);
        assertNotNull(p);
        assertEquals(WORK_GLOVES, p.goal().outputId());
        assertTrue(p.scrollBase());
        assertEquals(3.0, p.gain());
        assertEquals(1000, p.fees());

        Map<String, Integer> why = new HashMap<>();
        BotCraftManager.bestPlan(bot(40), have, 10_000, false, (r, reason, gain) -> why.merge(reason, 1, Integer::sum));
        assertEquals(1, why.get("needs scrolling, and it doesn't scroll its gear"));
    }

    @Test
    void craftDoesWhatTheScriptDoes() {
        Character bot = bot(20);
        Client c = mock(Client.class);
        AbstractPlayerInteraction cm = mock(AbstractPlayerInteraction.class);
        when(bot.getClient()).thenReturn(c);
        when(c.getAbstractPlayerInteraction()).thenReturn(cm);
        when(bot.getMeso()).thenReturn(5_000);
        when(cm.haveItem(anyInt(), anyInt())).thenReturn(true);
        when(cm.canHold(anyInt(), anyInt())).thenReturn(true);

        assertTrue(BotCraftManager.craft(bot, new BotCraftManager.Step(recipe(1022004, SCREW), 2)));
        verify(cm).gainItem(BRONZE_PLATE, (short) -2);
        verify(cm).gainItem(STEEL_PLATE, (short) -2);
        verify(cm).gainItem(SCREW, (short) 30);
        verify(bot).gainMeso(0);

        assertTrue(BotCraftManager.craft(bot, new BotCraftManager.Step(recipe(1022004, JUNO), 1)));
        verify(cm).gainItem(LEATHER, (short) -15);
        verify(cm).gainItem(JUNO, (short) 1);
        verify(bot).gainMeso(-1000);
    }

    @Test
    void craftStopsWhenShort() {
        Character bot = bot(20);
        Client c = mock(Client.class);
        AbstractPlayerInteraction cm = mock(AbstractPlayerInteraction.class);
        when(bot.getClient()).thenReturn(c);
        when(c.getAbstractPlayerInteraction()).thenReturn(cm);
        when(bot.getMeso()).thenReturn(500);
        when(cm.haveItem(anyInt(), anyInt())).thenReturn(true);
        when(cm.canHold(anyInt(), anyInt())).thenReturn(true);

        assertFalse(BotCraftManager.craft(bot, new BotCraftManager.Step(recipe(1022004, JUNO), 1))); // fee 1000
        verify(cm, never()).gainItem(anyInt(), anyShort());
    }
}
