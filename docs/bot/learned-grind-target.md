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

`tools/learned/` (Python): read the logs, label each pick with its EXP per second over the hold
(zero for a pick abandoned without a hit), and fit a small model that predicts that value from the
candidate's features. Start with a linear model or a few shallow trees; export the weights as JSON.

Evaluation gate, before any bot runs it: on held out days, estimate the EXP per second the model's
picks would have earned with inverse propensity weighting over the logged picks, and compare with the
rule. Report both, with the number of picks and the variance. The model must beat the rule by a
margin larger than the noise.

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
