package server.bots;

import client.Character;
import server.bots.BotGrindPlanner.Recommendation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.IntToDoubleFunction;

/**
 * Capped bots hunt the scrolls their fleet lacks, so the main farmers can grind (owner decision). A bot at its
 * training target has no exp to give up, so its plan is a farm of the fleet's shortfall: every equip scroll some
 * mob drops that members of the same owner's fleet could use ({@link BotOfferManager#takers}, from the roster
 * snapshot, offline members included) beyond the copies the fleet already holds (every member's and bin's
 * {@code scrollsHeld}). Each wanted scroll weighs its shortfall times its expected stat gain per use (success times
 * {@link BotScrollManager#marketStatValue}), and the hunt farms that basket ({@link BotGrindAdvisor#recommendFarmBasket})
 * on maps the bot can reach and damage. Loot reaches the takers through the bins ({@link BotScrollRouting}). With
 * nothing wanted or nothing farmable the bot stops at its cap as before. Hunting levels the bot past its target: the
 * target stops grinding for exp, it is not a level ceiling.
 */
final class BotScrollHunt {
    private BotScrollHunt() {}

    /** Per scroll id the fleet lacks, its weight: copies short (takers less copies held) times the expected gain of
     *  one use. Pure over the snapshot and the item rules. */
    static Map<Integer, Double> wanted(BotStorageMuleManager.Directory dir, int hunterId,
                                       BotOfferManager.ScrollRules rules, Set<Integer> candidates) {
        Integer owner = dir == null ? null : dir.ownerOf().get(hunterId);
        List<BotStorageMuleManager.Member> fleet = owner == null ? List.of()
                : dir.membersByOwner().getOrDefault(owner, List.of());
        if (fleet.isEmpty()) {
            return Map.of();
        }
        Map<Integer, Integer> held = new HashMap<>();
        for (BotStorageMuleManager.Member m : fleet) {
            m.scrollsHeld().forEach((sid, q) -> held.merge(sid, q, Integer::sum));
        }
        Map<Integer, Double> out = new HashMap<>();
        for (int sid : candidates) {
            if (!rules.offerable(sid)) {
                continue;
            }
            int shortBy = BotOfferManager.takers(rules, fleet, hunterId, sid).size() - held.getOrDefault(sid, 0);
            Map<String, Integer> st = rules.stats(sid);
            if (shortBy <= 0 || st == null || st.getOrDefault("cursed", 0) > 0) {
                continue;
            }
            double ev = BotScrollManager.effectiveSuccessPct(st.getOrDefault("success", 0)) / 100.0
                    * BotScrollManager.marketStatValue(st);
            if (ev > 0) {
                out.put(sid, shortBy * ev);
            }
        }
        return out;
    }

    /** What the fleet wants this bot to hunt now, from the live snapshot. */
    static Map<Integer, Double> wanted(Character bot) {
        return wanted(BotStorageMuleManager.directory(System.currentTimeMillis()), bot.getId(), BotOfferManager.rules(),
                BotGrindAdvisor.droppedScrollIds());
    }

    /** The hunt plan for a capped bot: the farm of the fleet's shortfall, or null when nothing is wanted or farmable. */
    static Recommendation recommend(BotEntry entry, Character bot, IntPredicate mapAllowed, IntToDoubleFunction weight,
                                    IntToDoubleFunction competitors) {
        Map<Integer, Double> want = wanted(bot);
        return want.isEmpty() ? null
                : BotGrindAdvisor.recommendFarmBasket(entry, bot, want, mapAllowed, weight, competitors);
    }
}
