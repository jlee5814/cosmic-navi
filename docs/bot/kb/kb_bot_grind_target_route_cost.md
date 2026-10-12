---
name: Grind target route cost (rope top flip, #48)
description: A grind candidate in another region was priced by its edge costs alone, so a mob past a free portal beat one a few steps away. Fixed in #50 by pricing every candidate with BotNavigationManager.routeTravelCost. Verified 2026-10-12 on 800030000.
type: project
---
# Grind target route cost

**Symptom (#48):** SipsBuddy26 (Spearman, lv33) on Forest of Animals (800030000) earned 5k then 528 EXP/h
for over an hour, moving the whole time (never flagged frozen), hanging around the rope at x 223
(y -353 to -152). Path log captures every 15 s showed `grindTarget` flipping between a Cloud Fox on the
rope top platform (475, -356) and one at (261, -1016), reached by `DROP r12->r22` then a portal at
(906, 74). Last swing 218 s old.

**Root cause:** `BotCombatManager.graphPathCost` priced a same region candidate with a walk estimate,
but a cross region one with the sum of `edge.cost` only. The walk to the first edge and from the last
edge to the mob were free. A portal edge costs 0, so a region past a portal looked almost free, and the
crowd bonus (400 per extra mob, cap 3,000) pushed it below a mob 231 px away. On the platform the far
group won; lower on the rope it fell outside `GRIND_SEEK_RANGE` (800 px straight line), so the near mob
won again. The 3 s commit window did not help, because a target leaving seek range is dropped at once.

**Fix (#50):** `BotNavigationManager.routeTravelCost(graph, route, fromIndex, from, startRegion,
targetRegion, targetPos)` is the one route cost: each in-region leg to the next edge's launch, the edge
(`routeEdgeCost`, rope launch height aware), and the last leg. `committedRouteRemainingCost` and the
grind scorer both call it. Test: `BotCombatManagerTest.shouldChargeTheWalkAtBothEndsOfAPathWhenPickingAGrindTarget`.

**How to spot the class:** a bot moving but earning near zero EXP, `lastExec agoMs` in the minutes,
and the path log's `goal=` alternating between two targets in different regions. Capture with
`/api/bot/pathlog` (two calls, about 6 s apart) a few times in a row and tally the goal runs.

**Repro without the boat ride:** `/api/spawnbot?id=` then `/api/command` `moveattack` with
`maps:[<mapId>]`; the bot travels there itself (Perion to Zipangu took about 2 minutes by World Tour).
