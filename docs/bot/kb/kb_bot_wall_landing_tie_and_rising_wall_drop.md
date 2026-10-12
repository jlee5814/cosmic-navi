---
name: Wall vs landing tie and walk off drops into a rising wall (graph v73)
description: Two New Leaf City sweep traps fixed in v73. Air steps that cross a floor and reach a wall resolved to the wall and fell through; walk off drops were authored into a wall rising from the region end. Read before touching resolveAirCollision, findGroundCollision or addDirectionalDropEdge.
type: project
---
# Wall vs landing tie, and walk off drops into a rising wall (graph v73)

Found by working down the sweep's stuck moves by traffic (New Leaf City 600000000 is the busiest map in
the EXP log). Both reproduce in `BotEdgeSweepCli`.

## Landing lost to a wall in the same air step (physics)

- **Symptom:** JUMP r137->r121 (5167,479)->(5137,409) on NLC: the bot reached the apex at y 403, hit the
  tall layer 0 wall at x 5136, fell back pressing into it and dropped through the y 409 platform to 479.
  Platforms at 409 and 479 run straight through that wall.
- **Root cause:** `findGroundCollision` scans x columns and reported a landing at the progress of the
  column where it saw the floor. Falling almost straight down against a wall there is one column, so the
  floor came out at progress 1.0, tied with the wall, and `resolveAirCollision` (strict `<`) kept the
  wall, placing the bot below the floor it had crossed.
- **Fix:** `landingAtX` reports the progress where the path reaches the floor's height. One SSOT, so the
  builder's offline fall simulator and the live integrator agree.
- **Effect in the sweep:** on NLC, Sleepywood 105070200 and 101020003, failures dropped from 32 to 22;
  105070200 and 101020003 went to 0. The live give up seen on 101020003 (`gave up a JUMP edge`, see
  [[kb_bot_edge_give_up]]) was this class.

## Walk off drop into a wall rising from the region end (builder)

- **Symptom:** DROP r103->r126 (1748,372)->(1680,434) stepX -6 on NLC: the bot walked left to x 1718 and
  stood in the corner. Region 103 is a valley from x 1715 to 1748; its left end is the foot of a 6 px
  wall up to the chain on the left (region 100), reached by a JUMP edge.
- **Root cause:** the builder tested the first step off the end from the end itself. `wallCollision`
  ignores a wall at the takeoff point (`progress <= 0`), so the step passed and lost ground. A walking
  bot never stands on the end: the wall stops it a step short.
- **Fix:** `addDirectionalDropEdge` also tests the step into the end from one step inside
  (`from.pointAt(endpoint.x - stepX)`) and skips the drop when a wall blocks it. Also removed the stuck
  drop on 105070200 at (-277,1000).
- **Tried and dropped:** validating with `simulateWalkOffLanding` alone. It lands exactly on the wall's
  x on the way and passes through x 4077's 9 px wall, where the executor's per step preview
  (`canWalkGroundStep` with the start height) is blocked, so it authored a new stuck drop at x 4037.

## Still open

- 101000301 straight DROP (2333,-359)->(2333,-310): stays in the sweep, not yet diagnosed.
