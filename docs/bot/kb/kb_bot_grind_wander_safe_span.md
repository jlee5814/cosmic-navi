---
name: Grind wander must use the steering's safe span
description: A grinding bot with no target wanders; its wander spot has to come from the same edge clamp the steering uses (BotMovementManager.grindSafePoint), and with nothing in seek range it walks toward the best reachable mob on the map. Read before touching resolveNoGrindTargetPosition, the patrol wander or GRIND_EDGE_MARGIN.
type: project
---
# Grind wander and the steering's safe span

**Symptom (#588):** SipsBuddy1 froze for 20 min at a time on Twisted Time 220060301, x about -690, y 1927, until
fleet ops rescued it. Pathlog: `Goal (-646,1927) [grind-wander]`, same region, `nav=same-region edge=none`, GND,
`*** STUCK ***`, `grindTarget=<none>`, no grind decision for minutes.

**Cause:** two halves.
1. The no-target wander picked x anywhere on the region, and re-picked only within `STOP_DIST` (30) of the spot.
   The grind steering (`adjustGrindingTargetPosition`, nav `adjustPathTarget`) clamps a target `GRIND_EDGE_MARGIN`
   (40) in from each edge. A spot inside the margin was clamped short, the bot stopped at the clamp, more than
   30 px from the spot, and never re-picked. The ledge there is 81 px wide (x -719..-638), so the clamp window was
   1 px and most picks froze the bot; on wide regions the same stall lasts until a mob comes in range.
2. The wander stayed inside the current region, so a bot on a ledge with no mob within `GRIND_SEEK_RANGE` (800)
   could never walk toward the mobs.

**Fix:** `BotMovementManager.grindSafePoint(region, point)` is the SSOT for the edge clamp; the steering, the path
target and both wanders (grind and patrol) use it, so a wander spot is always one the steering reaches. When
`rankGrindTargets` finds nothing in seek range, `BotCombatManager.findGrindTargetBeyondSeekRange` ranks the whole
map with the same rules and the wander heads to the best reachable mob's region; normal targeting takes over once
a mob is in range. Patrol wander stays inside its region by design.

**Tests:** `BotCombatManagerTest.noGrindWanderSpotStaysWhereTheGrindSteeringCanReach`,
`grindingBotWithNothingInSeekRangeWandersTowardTheNearestReachableMob`.
