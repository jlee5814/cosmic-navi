---
name: kb_bot_nav_negative_edge_cost
description: "A nav edge whose cost wrapped past Integer.MAX_VALUE to -2147483329 looped A* forever on two bot tick threads (2 cores at 100%, two frozen bots, Oct 20, #218). The sentinel to watch for, how it was found, and the invariant now in addEdge"
---

**Symptom (Oct 20).** The game server sat at about 200% CPU with 11 bots. Per thread CPU (sum utime+stime from
`/proc/1/task/*/stat` inside the container, two snapshots 10 s apart) showed two `TimerManager-Worker` threads at
about 100% each. `jstack 1` six times, one second apart: both sat at `BotNavigationManager.reconstructPath`, called from
`BotCombatManager.graphPathCost` in target scoring, for 75 minutes. SipsBuddy7 (220040100) and SipsBuddy46
(220040300) were frozen, and fleet ops couldn't rescue them: the rescue runs on the bot's own tick.

**Root cause.** The walk off drop builder (v77) picks its landing from several walk phases but priced the fall with a
second simulation from the nominal step off the ledge. When another phase made the landing, that fall could miss and
return `Integer.MAX_VALUE`, the estimators' no landing sentinel. Adding the runway wrapped it to -2147483329 (DROP r14
to r16 on 220040100, DROP r3 to r15 on 220040300; both present at v78 and v79). A negative edge lets A* keep relaxing
around a loop, so cameFrom forms a cycle, and `reconstructPath` has no budget.

**Fix (#218, graph v80).** The drop costs the fall that landed (`landing.timeMs()`), and `addEdge`, the one choke
point for every edge, refuses a negative or `MAX_VALUE` cost. `BotNavEdgeCostTest` pins the map.

**Lessons.** Any `Integer.MAX_VALUE` returned as a cost is a sentinel; never add to it. To find a hot thread in the
server, sample per thread CPU first, then take repeated stacks: one stack lies, six agree. A probe that reproduces a
hang must stop at the first one: one spinning thread per hung search took the Mac to 1,200%.
