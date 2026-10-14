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
  enough hurts again once `MOB_HIT_COOLDOWN_MS` has passed. Then the usual post landing ground ticks, holding
  `holdDir` (a jump's launch key, which brakes the recoil's slide as the executor does once grounded).
- Jumps (`simulateJumpLandingWithPostLandingTicks`) face their launch key. Walk off drops skip the recoil (facing
  0): the executor steers to the landing point in the air, so the facing at touch down is unknown, and v77's
  settle matched 118 of 120 swept walk offs that hurt.
- Straight drops face wherever the bot last walked, so the executor turns first, as a player would
  (`BotNavigationGraphProvider.straightDropFacing`, called by `BotNavigationManager.tryExecuteDrop` before
  `queueDownJump`). A straight drop that hurts gets one edge per region a facing's recoil rests in
  (`dropLandings`, which feeds both the anchor loop and the window probe).
- A landing that hurts ends where it rests: straight drop and jump edges take the rest point as `endPoint`, so the
  end point lies in `toRegionId`. The router heads for an edge's end point; one on the foothold touched first sent
  it to that region instead. The router's straight drop landing (`routeLandingPoint`) shifts the launch x by the
  edge's rest offset.
- Straight drops that don't hurt target where they land, exactly as before. The first v78 run settled them too and
  lost 4 working ways (100030000, 221022400, 540000200, 600020100: drops of 338 to 872 px).
- Live physics: `BotPhysicsEngine.landOnGround` keeps the bot's facing. It took facing from the landing slide, so a
  straight drop onto a slope turned the bot into the slide and the recoil went the wrong way. The client turns only
  on a key press, as `applyGroundMotion` already modelled.
- Water has no fall: the live swim integrator tracks no peak, so `JumpLanding.fallPx` is 0 on swim maps.

## Result

Nav gate, 253 maps, landing elsewhere on base (main after v77): 130 (129 once the sweep budget covers long
flights). Run h ships: 4 left, all walk offs; 0 stuck (1 on base); 399 trips probed, 0 lost. Each run:

| Run | Change | Landing elsewhere | Stuck | Lost ways | Gate |
|---|---|---|---|---|---|
| a | settle every landing that hurts, straight drops included | 8 | 0 | 4 | FAIL |
| b | settle only straight drops that hurt; both facings must agree | 7 | 0 | 1 | FAIL |
| c | either facing; the executor turns first | 37 | 0 | 0 | PASS (old compare) |
| d | c, target the rest region; landing keeps facing | 64 | 0 | 0 | PASS (old compare) |
| e | d, a straight drop ends at its rest point | 6 | 3 | 1 | FAIL |
| f | e, no fall in water; jumps end at the rest point; an edge per facing | 5 | 0 | 2 | FAIL |
| g | f, walk offs keep v77's settle; main's compare (landings gated) | 5 | 0 | 1 (+1 new landing) | FAIL |
| h | g, the jump settle keeps the launch key after a recoil; sweep budget covers the flight | 4 | 0 | 0 | PASS |

Runs c and d passed only because the old compare didn't gate landings (#89 gates them); they landed elsewhere
more: the turn was undone. In c, the slope at the landing turned the bot
(30 of 37). In d, the drop's end point sat on the foothold it touched first, in another region than its target,
so the router headed for that region and took the drop facing the other way (107000500 x 441, 200000000 x 2441,
105060000 x -312). In e, the 3 stuck were swim drops (230030100) moved onto rest points no swimmer reaches, and the
lost way (105050200 (2126,-220) to (2126,863)) rested below only facing left, a region the anchor never authored.
In f, a walk off (105070001 x 2410) settled with the launch facing, but the executor steers to the landing point
in the air and the bot touched down facing back, as on base; walk offs went back to v77's settle. In g, the new
landing was a 5586 px fall (101020000 x 864) still in the air when the 8 s sweep budget ran out, and the lost way
(101010102 (-425,635) to (-153,1859)) was a jump whose window shrank to 3 px at the platform's end: the settle
dropped the key after the recoil and slid 3 px off r12, where live bots stay (base 9 of 10).

## Limits

- A Power Stance buff blocks the knockback live; the graph is built for the base profile and assumes no stance.
- The recoil arc assumes no key held; a live bot steering toward its next waypoint mid recoil can drift.
- Vertical jumps (step 0) never fall far enough to hurt, so they skip the recoil.
- Walk off edges still end at the touch point (`matchesDirectionalDrop` compares live landings with it) and settle
  without the recoil. The 4 landings elsewhere left after run h are all walk offs.
- The lab can't use portals, so a probe whose route takes an in map portal fails on both sides.
