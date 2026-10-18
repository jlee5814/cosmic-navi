---
name: kb_bot_en_route_attack_reach
description: "En route attacks picked the best ranked mob within 400 px, in reach or not; on Snowy Hill a Leatty outranked the Jr. Pepe in the lane and a Spearman was knocked back for 11 hours. Fix: the en route pick is the best ranked mob the attack reaches. How to diagnose with botdebug"
---

**Symptom (Oct 17).** SipsBuddy26 (Spearman 48) sat on Snowy Hill 211000200 from 13:09 to past midnight, about 11 h.
It walked west toward west00 (-1584,33), was knocked back at x -900 every hit cooldown, drank pots, ran to El Nath
to restock every few hours, and came back to the same spot. Fleet ops never rescued it (capped hunter, no usual
exp rate). Snowy Hill is El Nath's only walking exit, so any bot leaving El Nath west is exposed.

**Root cause.** `BotManager.tryEnRouteOpportunityAttack` (travel's fire in passing attack, no chase) took its target
from `BotCombatManager.findFollowAttackTarget`: every local mob within 400 px, ranked by distance, foothold and the
AoE cluster bonus. A Leatty at (-1056,33) anchored a cluster and outranked the Jr. Pepe at (-916,33), which stood in
the lane on the next foothold. The plan for the Leatty was out of reach, so the bot swung at nothing, walked into
the Pepe and took its touch. Mobs with no player on the map have no controller and stand on their spawn points, so
the Pepe never moved and the loop never broke.

**Fix.** `BotCombatManager.findEnRouteAttackTarget`: the same ranking (`rankFollowAttackTargets`, the SSOT shared
with `findFollowAttackTarget`), first mob whose plan is in the attack's reach (`isTargetInAttackRange`). A plan can
exist and still not reach (skill plans), so plan != null is not enough. Grind and follow callers still take the
best ranked mob, since they can reposition. Verified live: the same westward approach killed the Pepe and left by
west00 in 15 s. Test: `BotCombatManagerTest.enRouteTakesTheMobInReachOverABetterRankedOneOutOfReach`.

**Diagnosing a bot that takes hits and never swings.** `/api/botdebug?id=` detail now carries `lastHit` (mob id,
where it stood, ms ago), `enRoute` and `enRouteTarget` (the en route decision and its target), `idleLeech`, and
`nearMobs` (mobs within 500 px with the en route verdict per mob). Poll it once a second while the bot walks into
the spot. A target that is not the mob in `lastHit`, with `out-of-range`, is this bug.

**Side notes.** Under the `moveto` and quiet `move` operator commands the bot took no touches walking through mobs,
so reproduce with autopilot or `moveattack`. The `/api/settings` en route knobs (OPPORTUNITY_*) were not the cause.
See [[kb_bot_fleet_time_loss]], [[kb_bot_attack_planning_flow]].
