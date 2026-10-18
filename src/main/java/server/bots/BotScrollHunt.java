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
 * snapshot, offline members included) beyond the copies of it and its substitutes the fleet already holds (every
 * member's and bin's {@code scrollsHeld}). Each wanted scroll weighs its shortfall times its expected stat gain per use (success times
 * {@link BotScrollManager#marketStatValue}), and the hunt farms that basket ({@link BotGrindAdvisor#recommendFarmBasket})
 * on maps the bot can reach and damage. Loot reaches the takers through the bins ({@link BotScrollRouting}). With
 * nothing wanted or nothing farmable the bot stops at its cap as before. Hunting levels the bot past its target: the
 * target stops grinding for exp, it is not a level ceiling.
 */
final class BotScrollHunt {
    private BotScrollHunt() {}

    /** Per scroll id the fleet lacks, its weight: copies short times the expected gain of one use. Scrolls of one
     *  gear type that grant the same stats are substitutes (a 10%, a 60% and a 100% weapon attack scroll fill the same
     *  slots), so they share one shortfall: the most members any of them suits, less every copy of any of them the
     *  fleet holds. Pure over the snapshot and the item rules. */
    static Map<Integer, Double> wanted(BotStorageMuleManager.Directory dir, int hunterId,
                                       BotOfferManager.ScrollRules rules, Set<Integer> candidates) {
        Integer owner = dir == null ? null : dir.ownerOf().get(hunterId);
        List<BotStorageMuleManager.Member> fleet = owner == null ? List.of()
                : dir.membersByOwner().getOrDefault(owner, List.of());
        if (fleet.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> heldByKind = new HashMap<>();
        for (BotStorageMuleManager.Member m : fleet) {
            m.scrollsHeld().forEach((sid, q) -> {
                String kind = substituteKind(rules, sid);
                if (kind != null) {
                    heldByKind.merge(kind, q, Integer::sum);
                }
            });
        }
        Map<String, Integer> takersByKind = new HashMap<>();
        Map<Integer, Double> evById = new HashMap<>();
        for (int sid : candidates) {
            String kind = substituteKind(rules, sid);
            if (kind == null) {
                continue;
            }
            Map<String, Integer> st = rules.stats(sid);
            double ev = BotScrollManager.effectiveSuccessPct(st.getOrDefault("success", 0)) / 100.0
                    * BotScrollManager.marketStatValue(st);
            int takers = BotOfferManager.takers(rules, fleet, hunterId, sid).size();
            if (ev > 0 && takers > 0) {
                evById.put(sid, ev);
                takersByKind.merge(kind, takers, Math::max);
            }
        }
        Map<Integer, Double> out = new HashMap<>();
        evById.forEach((sid, ev) -> {
            String kind = substituteKind(rules, sid);
            int shortBy = takersByKind.getOrDefault(kind, 0) - heldByKind.getOrDefault(kind, 0);
            if (shortBy > 0) {
                out.put(sid, shortBy * ev);
            }
        });
        return out;
    }

    /** The substitute group of a routable scroll: its gear type (the scroll id's category) and the stats it grants;
     *  null for a scroll that isn't routed or grants nothing, or a cursed one. */
    static String substituteKind(BotOfferManager.ScrollRules rules, int sid) {
        Map<String, Integer> st = rules.offerable(sid) ? rules.stats(sid) : null;
        if (st == null || st.getOrDefault("cursed", 0) > 0) {
            return null;
        }
        java.util.TreeSet<String> stats = new java.util.TreeSet<>();
        st.forEach((k, v) -> {
            if (v != null && v > 0 && !k.equals("success") && !k.equals("cursed")) {
                stats.add(k);
            }
        });
        return stats.isEmpty() ? null : (sid / 100) + ":" + String.join(",", stats);
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
        if (want.isEmpty()) {
            return null;
        }
        entry.huntWanted = java.util.Set.copyOf(want.keySet());
        return BotGrindAdvisor.recommendFarmBasket(entry, bot, want, mapAllowed, weight, competitors);
    }
}
