---
name: kb_bot_fall_damage_recoil_landings
description: Graph v78. Why almost every remaining move landing elsewhere was a long fall (fall damage knocks the bot back off the ledge or onto a rope), how settleLanding models the recoil, and why short straight drops are left alone. Read before changing landing simulation, fall damage, knockback or straight drop edges.
type: project
---
# Long falls land elsewhere: the fall damage recoil (graph v78)

## Finding

After v77 the sweep still had 133 moves landing on another platform than planned. Split by fall length (peak to
landing; fall damage starts at 890 px, `FALL_DIST_THRESHOLD_PX`):

| Swept on main | Fall under 880 px: work | Land elsewhere | Fall 880 px or more: work | Land elsewhere |
|---|---|---|---|---|
| Straight drops | 13,201 | 0 | 251 | 76 |
| Jumps | 28,637 | 10 | 295 | 42 |
| Walk off drops | 10,274 | 3 | 118 | 2 |

120 of 133 were long falls. A trace (Ellinia tree 101020000, straight drop from (-172,-3819)) showed the fall
itself is exact: the bot lands on the authored point (-172,-2467). Then fall damage
(`BotCombatManager.applyFallDamage`) knocks it back (`velY -21.9`, `airVelX` 9 per tick, away from its facing), and
knockback recovery holds UP, so it grabbed the rope at x -98. On a narrow ledge the same recoil throws it off the
edge and down again (the Eos Tower 221023400 jumps fell to the bottom of the tower).

The physics was right (the recoil follows captured client falls); the builder never asked where a landing that
hurts comes to rest.

## Fix

- `BotPhysicsEngine.JumpLanding.fallPx`: peak to landing descent, tracked in `simulateLanding`.
- `BotPhysicsEngine.settleLanding(map, landing, facingDir, holdDir, profile, ticks)`: a landing whose fall deals
  damage flies the recoil arc away from `facingDir` (`BotCombatManager.knockbackVelY`, `knockbackAirVelX`, the
  numbers the live hit uses), no key held; a rope the arc reaches is grabbed (`lostGround`); a recoil that falls far
  enough hurts again once `MOB_HIT_COOLDOWN_MS` has passed. Then the usual post landing ground ticks.
- Jumps (`simulateJumpLandingWithPostLandingTicks`) and walk off drops face their launch direction. Straight drops
  face wherever the bot last walked, so one that hurts must settle in the same region facing either way, or it is
  no edge.
- Straight drops that don't hurt target where they land, exactly as before. The first v78 run settled them too and
  lost 4 working ways (100030000, 221022400, 540000200, 600020100: drops of 338 to 872 px).

## Result

Gate, 253 maps: see the PR. First run (settling every straight drop): landing elsewhere 132 to 8, 0 stuck, 4 lost
ways, FAIL.

## Limits

- A Power Stance buff blocks the knockback live; the graph is built for the base profile and assumes no stance.
- The recoil arc assumes no key held; a live bot steering toward its next waypoint mid recoil can drift.
- Vertical jumps (step 0) never fall far enough to hurt, so they skip the recoil.
