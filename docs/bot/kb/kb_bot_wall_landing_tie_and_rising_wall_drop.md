---
name: Wall vs landing tie, rising wall drops, ground truncation and held key arcs (graph v73 to v75)
description: Sweep traps fixed by traffic in v73 and v74, plus the ones still open. Air steps that cross a floor and reach a wall resolved to the wall and fell through; walk off drops were authored into a wall rising from the region end. Read before touching resolveAirCollision, findGroundCollision or addDirectionalDropEdge.
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

## Ground detection swapped a slope for the branch above it (v74)

- **Symptom:** 101000301 straight DROP r3->r5 from (2333,-359): walking left on branch fh 26 toward the
  drop window (x 2320 to 2347), the bot hopped up onto fh 25 at x 2360 and looped r2 to r6 to r3.
- **Root cause:** `findGroundFoothold` / `findGroundPoint` probed from the position and from the top of
  the slope-up band and kept the closer. fh 26's height at x 2360 truncates a pixel above the stored y,
  so the position probe skipped it and found nothing close; the band probe returned the topmost
  foothold in the band, fh 25, 9 px up.
- **Fix:** also probe 2 px above the position and keep the closest ground of the three.

## Still open (after v75)

- 220000100 JUMP r4->r5 from (597,83): region 4 is fh 25, drawn right to left (x1 > x2). The ground
  probe never stands on it, so the region is a phantom: the sweep spawns a bot there and it snaps onto
  the box top at y 77. Harmless live (nothing can enter r4); the builder should not make regions from
  footholds the ground probe skips. 7 EXP log visits.

## Offline arc stopped dead at a wall; the executor keeps the key held (v75)

- **Symptom:** 541000000 JUMP (-908,-258)->(-914,-284) and 600020200 JUMP (-162,-654)->(-168,-700): the
  jump met a low wall on the way up, and the bot landed back at the start.
- **Root cause:** `simulateLanding` set `stepX = 0` at a wall and fell straight down onto the near
  platform. The executor sets `moveDir` from the launch velocity (`tickAirborne`) and never clears it in
  flight, so the launch key stays held. Above the input band (walk speed / 14) holding is a no-op, which
  is why arcs match before any wall; after a wall zeroes `airVelX`, `applyAirSteering` accelerates the bot
  again at 200 px/s^2 up to the band (about 0.43 px per tick), and once it rises past the wall's top it
  drifts over and falls on the far side.
- **Fix:** `simulateLanding` applies the same held key steering every tick (profile walk speed, map
  slipperiness). Test: `BotPhysicsEngineTest.shouldCarryAJumpOverAWallTopWhenTheLaunchKeyIsStillHeld`
  checks the arc and a live `stepAirborne` flight land on the same spot past the wall.
