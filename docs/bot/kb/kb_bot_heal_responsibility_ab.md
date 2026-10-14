---
name: kb_bot_heal_responsibility_ab
description: A/B of how a cleric bot heals, an action (heal when the owner asks), today's standing rule (heal anyone in the box under 90%) and a richer keep the party alive responsibility, in a seeded arena sim (BotHealArena). Results, what they mean, the sim's limits, and how to rerun. Read before changing cleric healing or building a goal or responsibility layer for bots.
type: project
---
# Heal A/B: action, standing rule, responsibility (2026-10-14)

## Question

Does a cleric holding keep my party alive as a standing goal, with triggers to reposition and budget MP, lose
fewer party members than today's rule, and what does it cost in exp/hr?

## Answer

- **Deaths: no difference.** Nobody died in any arm, mob tier or pot supply once the sim had live potting,
  regen and resupply. Bots autopot at 70% (`AUTOPOT_HP_THRESH`) with far more potion throughput than touch damage
  can outpace under the 1.5 s hit invincibility (`MOB_HIT_COOLDOWN_MS`).
- **Standing beats action, by a lot.** Today's rule against heal on request: 240 to 1480 fewer HP pots an hour,
  2 to 30 fewer resupply trips an hour when pots are limited, and with 40 pots each 6k to 39k more party exp an
  hour. With unlimited pots the action arm gets 8k to 30k more exp, because its cleric bolts instead of healing.
- **The richer responsibility added nothing.** B2 (today's 90% top up plus one more hit urgency, heal the urgent
  first, walk to an endangered member out of range, save MP under a reserve) matched today's rule within noise in
  every cell. Those triggers almost never fire beyond what the 90% rule already does.
- **Being stingy cost.** B (top up only near the potion line) used 140 to 850 more HP pots an hour than today's
  rule and made more resupply trips; it gained exp mostly when pots were free.

**Takeaway:** what matters is holding the goal over time and across the box. Clever triggers on top of a simple
rule don't help here. Heal is cheap (a little MP restores 2451 HP across the box at level 43); a potion restores
300. So the right standing rule heals eagerly, and today's does.

## Arms

| Arm | Code | Rule |
|---|---|---|
| A1 action | `BotHealArena` (arena only) | Owner types heal under 50% HP after 0.8 to 2 s; cleric walks into range, casts once |
| A2 today | `BotHealPolicy.Reactive` (live) | Cast if the cleric or anyone in the Heal box is under 90% |
| B responsibility | `BotHealResponsibility` (test code) | Goal, scope, MP reserve, leash; urgent on one more hit, reach, top up one remembered hit from the pot line |
| B2 | `new BotHealResponsibility(true)` | B with today's 90% top up |

## Results (20 seeds, 30 minutes, density 8 mobs per 1000 px; per hour)

| Mob, pots each | Arm | HP pots | Resupply trips | Exp |
|---|---|---|---|---|
| Dark Stone Golem 5130102, unlimited | A1 | 3607 | 0 | 390k |
| | A2 | 2131 | 0 | 360k |
| | B | 2702 | 0 | 383k |
| Dark Stone Golem, 150 | A1 | 3275 | 21.1 | 334k |
| | A2 | 2062 | 11.4 | 337k |
| | B | 2691 | 15.9 | 340k |
| Iron Boar 4230400, 40 | A1 | 1310 | 40.2 | 229k |
| | A2 | 347 | 10.5 | 268k |
| | B | 1168 | 35.2 | 242k |

Full grid (Iron Boar, Stone Golem 5130101, Dark Stone Golem; densities 4 and 8; pots unlimited, 150, 40) with
paired 95% t intervals: rerun below. B2 minus A2 intervals include zero almost everywhere.

## The sim (`src/test/java/server/bots/BotHealArena.java`)

- From the game (SSOT): mob level, touch damage, accuracy, HP, exp (Mob.wz); Heal MP cost, box, split per member
  (`StatEffect.healPerTarget`), animation lock; Energy Bolt MP cost; touch damage and hit chance
  (`BotDefenseDataProvider.physicalTouchDamageAt`, `CombatFormulaProvider.calculateBotAvoidChance`); hit
  invincibility, heal walk window, jump heal (`BotCombatManager.Config`); autopot lines, `POT_STOP`, regen
  (`BotManager.Config`).
- Assumed (`BotHealArena.Assumptions`): 1D platform, movement, mob chasing, member stats and damage, potion sizes,
  60 s death downtime, 120 s resupply trip, the owner's human pot timing.
- Same seed per arm, with separate random streams for mobs, each member's touch rolls, damage dealt and the owner.
- An independent audit (2 rounds) found and got fixed: death loops after pots ran out, no jump heal, bolts in the
  heal walk window, one shared random stream, under 30% not normalized by time on the field, z instead of t.

## Limits

- Live, a bot following its owner does not go to town at `POT_STOP` (`requestResupplyErrand` needs autopilot); it
  keeps fighting and walks to its owner under 40% HP. The resupply trip prices pot use; the HP pots column is the
  direct measure.
- Live jump heal triggers on distance to the anchor (almost every cast while following); the sim triggers on
  distance to the follow spot. Slightly kinder to A2, the arm that casts most.
- B's hit tracking misses hits taken during its own heal lock (the heal hides them in the next HP look), and its
  urgent rule can cast on a member at full HP right after a big hit. Both weaken B; B2 shares the urgency code and
  still tied A2, so the conclusion holds.
- No mob skills or ranged attacks, one flat platform, one party shape (warrior owner, page, hunter, cleric).
- B's reach and MP saving are not wired live; B2 shows they would not pay.

## Rerun

On the Mac: `tmp/navgate-dev/arena_tiers.sh <branch> 4,8 20 30 <out> -1,150,40` (tests, then
`BotHealArenaCli <mobId> <densities> <seeds> <minutes> <potSupplies>` per mob). About 10 minutes for the grid.
