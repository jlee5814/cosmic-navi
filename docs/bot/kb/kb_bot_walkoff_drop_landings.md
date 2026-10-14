---
name: kb_bot_walkoff_drop_landings
description: Graph v77. Why 360 swept moves on main landed in a region other than the one their edge targets (triage by edge type), and how walk off drops now model every walk carry phase. Read before changing addDirectionalDropEdge, the post landing settle, or chasing landings elsewhere in a nav gate.
type: project
---
# Landings elsewhere and walk off drops (graph v77)

The nav gate (`tools/navgate/nav_gate.sh`) prints `landing elsewhere (not gated)`: sweep moves whose bot ended
grounded in a region other than the edge's target (`wrong-region` in `BotEdgeSweepCli`). Not stuck, the bot
replans from where it is, but each one is a route the planner believed and the bot didn't follow.

## Triage on main (a6b2b26, 253 maps)

| Kind | Count | What happened |
|---|---|---|
| Walk off drops | 230 | 144 landed 10 to 19 px past the authored landing (stairs, Henesys 100000000, Kerning 103000000) |
| Straight drops (down jump) | 76 | 61 landed on a lower platform, 15 fell off the map |
| Jumps | 54 | 15 on one map, 221023400 |

Walk off drops were the bulk and are what v77 fixes. Straight drops and the 221023400 jumps are still open.

## Cause: the fall started from the wrong place at the wrong speed

- `addDirectionalDropEdge` fell from the region's end. Live walking never stands on a pixel past the end: the
  tick that loses ground has already moved the bot 1 to 7 px out.
- A tick of top speed walking is 6 or 7 client ground steps (50 ms over 8 ms steps), so the bot leaves at 6 or
  7 px/tick, 1 to 6 or 1 to 7 px out. `BotPhysicsEngine.walkOffStepRange` gives the two speeds.
- On a narrow step the phase decides the landing. Henesys x 321: at 6 px/tick the bot lands on the 24 px stair at
  (349,113); at 7 it mostly clears it to (364,137). The sweep's bot takes one phase, so the old edge was flaky
  there and wrong-region one step down.

## Fix (v77)

Every phase (13 at base speed) is simulated from past the end, each landing settles for 3 ticks with no key
held (`settledRegionId`; the executor steers to the landing point once airborne, `selectDropWaypoint`), and the
drop targets the region where at least three in four settle. Fewer is a coin flip and no edge; the planner
takes another way (the gate found none lost). The endpoint is the nominal phase's landing when it agrees.

## What was tried first, and why it lost

All on the full gate (main: 355 to 358 landings elsewhere, 0 stuck).

| Variant | Landings elsewhere | Stuck | Problem |
|---|---|---|---|
| Fall from one step out, nominal phase only | 237 (95 map subset) | 2 | Narrow stairs at 7 px |
| Plus a 3 tick settle holding the walk key | 148 | 0 | Deleted good drops: the held key walks off a 26 px step a bot really stops on (pet walking road 100000202, `BotMovementSimulationLabTest.shouldUsePetWalkingRoadLocalWalkOffDrop`) |
| Settle with no key | 234 | 2 | Henesys stairs again: the 7 px phase clears the step |
| All phases must agree | 136 | 0 | Too strict: the pet road drop agrees in 10 of 13 and was dropped |
| Three in four (shipped) | 136 | 0 | |

## How to look at one

- `BotEdgeSweepCli out.tsv <mapId>` with `-Dbot.nav.cacheDir=/tmp/x` sweeps one map; compare base and head rows by
  `startX startY`.
- To see phases, loop `simulateFallLanding(map, end + out*dir, speed*dir)` over the phases and print the landing
  and `simulatePostLandingGroundTicks(..., 0, ...)`. That table is what told the stairs apart from the pet road.
