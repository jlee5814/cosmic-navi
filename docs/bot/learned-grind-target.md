# Learned grind target scorer

A learned layer for one decision: which mob a grinding bot chases next. The hand-written scorer
(`BotCombatManager.findGrindTarget`) stays in charge of what is allowed; the model only reorders the
candidates the rules already accept, and every step ships only when it beats the rules on data.

## Why this decision

- It runs often: every `GRIND_RETARGET_INTERVAL_MS` (400 ms) while grinding, for every bot.
- Its outcome is plain and fast: did the bot land a hit, how soon did the mob die, how much EXP came in
  before the next pick.
- The rules fail on it in ways tuning can't see. #48: a group past a free portal outscored a mob 231 px
  away on the bot's own platform, and the bot climbed a rope for hours without a swing. The cost bug is
  fixed, but the scorer is still a stack of weights (`graphCost`, crowd bonus, occupancy penalty, touch
  danger, accuracy, quest bonus) that nobody fitted against outcomes.

## Shape

```
candidates (rules)  ->  guard: reachable, inside seek range, not unreturnable
                    ->  rank: model score if a model is loaded, else the rule order
                    ->  pick: best, or with small probability another of the top k (logged)
                    ->  outcome: closed at the next pick
```

The rules remain the guard and the fallback. No model, a stale model, or a candidate the model has
never seen the like of, and the bot behaves exactly as today.

## Stage 1: the decision log (data first)

Every time a grind search commits to a target, append one JSON line to
`logs/bot-decisions/grind-target-<date>.jsonl`:

- **Context:** time, bot id, map, level, job, weapon type, HP and MP ratios, position, on a rope or not.
- **Candidates** (top 8 by rule order, one per graph region): mob id, level and HP ratio, dx and dy
  from the bot, route cost (`routeTravelCost`, after the crowd bonus and occupancy penalty), the rule's
  local score (touch danger, accuracy and quest terms folded in), region id, and how many mobs that
  region holds.
- **Pick:** index of the chosen candidate (0 is the rule's choice) and the probability it had.
- **Outcome**, written when the next pick closes this one: milliseconds held, EXP gained (level ups
  included), whether the target died, the share of its HP that dropped (a hit landed, from anyone),
  and HP the bot lost.

Exploration: on a fresh pick (no live target), with probability `GRIND_TARGET_EXPLORE` (default 0.1)
the bot takes the second or third rule candidate instead of the first, when its cost is within
`GRIND_TARGET_EXPLORE_BAND_MS` (1500) of the best.
This is the humanlike jitter the rules lack, and it makes the logged picks varied enough to learn from;
the logged probability lets us evaluate a new ranking against the old one without running it.

Volume: one line per committed pick, a few per minute per bot, well under a megabyte per bot per day.

## Stage 2: train offline

Start when the log holds enough to learn from: 20,000 picks across 10 or more maps, at least 300 of them
explored (`BotGrindTargetLog.READY_*`). `/api/learned/readiness` counts it, and the world map page shows it.

`tools/learned/train_grind_target.py` (plain Python; method in [its README](../../tools/learned/README.md)):

- Value of a pick: EXP earned minus what the hold would have earned at the bot's average pace.
- Model: ridge regression on 10 candidate features, rows weighted by one over their logged probability.
- Policy: the best scored candidate among those the logging policy could have taken, so it only reorders
  near ties on fresh picks and every choice is evaluable.
- Gate: on the latest 25% of decisions, self normalized inverse propensity replay compares model and rule;
  pass requires the readiness counts and a 90% bootstrap interval on the gain entirely above zero.

First run, 2026-10-12, 4,402 picks: the weights point the expected way (cheaper routes, mobs already damaged
and crowds score higher), but on held out time the model lost to the rule by 12.1 EXP per decision (90% CI
-23.3 to -1.7). The gate blocked it, as it should.

Second run, 2026-10-18, 147,345 picks over 105 maps (8,943 explored, readiness met): the model now leads.
On the held out quarter (36,575 decisions) it differs from the rule on 7,808 and earns +449.5 EXP per decision
above pace against the rule's +433.0, a gain of +16.5 (about 4%), but the 90% interval (-4.0 to +40.8) still
crosses zero, so the gate holds it. Mob HP, route seconds and horizontal distance carry the most weight; the
rule's own rank barely matters once those are known. Next levers: interactions (a linear model can't learn
that a far mob is fine when it is nearly dead), or more held out data.

Third run, same night, gradient boosted trees (`--model trees`: 80 trees of depth 3, learning rate 0.1, 32
quantile bins, minimum leaf weight 400). Both models on the same log (147,985 picks, 36,7xx held out):

| model | differs from rule | rule | model | gain | 90% CI | gate |
|---|---|---|---|---|---|---|
| linear | 7,879 | +455.8 | +470.7 | +14.9 | -6.2 to +36.5 | holds |
| trees | 8,342 | +455.3 | +481.0 | +25.7 (about 6%) | +2.3 to +49.8 | **passes** |

The trees split most on level gap and bot HP. Bot HP is the same for every candidate in a decision, so it
only matters through interactions (which mob to take when hurt), which is exactly what the linear model
cannot express. Caveat: two models were tried on one held out set, and the lower bound is small, so the
offline pass licenses stage 3, not a rollout. The live split is the real test.

## Stage 3: serve and compare live

- `BotGrindTargetModel` loads the JSON at startup (pure Java, no new dependency) and scores
  candidates; `findGrindTarget` orders by it when present.
- Split the fleet: half the bots on the model, half on the rule, assigned by bot id.
- Compare over at least a day: EXP per hour, picks abandoned without a hit, stall incidents from the
  responder (`/api/fleetops/incidents`). Keep the model only if EXP per hour improves and stalls do
  not rise.

## Not in scope

- Language models in the loop. A 400 ms decision can't wait on one; they stay with rare, high level
  choices (the strategist).
- Learning movement or combat execution. The sim has no mobs yet, so reinforcement learning against it
  would need mobs and combat added first.
- Other decisions (farm map choice, stuck recovery). The same log, train, gate, serve pattern applies
  once this one has paid off.
