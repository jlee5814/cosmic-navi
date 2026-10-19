# Learned grind target scorer, stage 2

`train_grind_target.py` reads the decision log the server writes (`logs/bot-decisions/grind-target-*.jsonl`,
stage 1), fits a small model of which grind picks pay, and replays held out time to check whether ranking by
the model would have beaten the rules. Plain Python, no dependencies. Design and stages:
[docs/bot/learned-grind-target.md](../../docs/bot/learned-grind-target.md).

```
python3 tools/learned/train_grind_target.py logs/bot-decisions                  # linear: tools/learned/out/grind-target-linear.json
python3 tools/learned/train_grind_target.py logs/bot-decisions --model trees    # trees:  tools/learned/out/grind-target-trees.json
python3 -m unittest tools/learned/test_train_grind_target.py
```

## What it does

1. **Value of a pick:** EXP it earned minus what its hold would have earned at that bot's average pace.
   The goal is EXP per hour, so a quick small kill and a slow big one compare fairly.
2. **Which picks count:** holds over 120 s (breaks, logouts, rescues) are dropped, and so is any line whose
   logged probability doesn't match the policy rebuilt from it.
3. **What the policy could have done:** rebuilt from the logged probability. 1.0 means only the rule's first;
   otherwise the first had 0.9 and each alternative within 1500 ms of its route cost had 0.1 split among them.
   These must match `BotCombatManager.cfg.GRIND_TARGET_EXPLORE` and `_BAND_MS` when the lines were written.
4. **Model:** either ridge regression or small gradient boosted trees (`--model trees`: squared loss, the same
   row weights, splits on 32 quantile bins, 80 trees of depth 3; trees can learn interactions). Both use 10 candidate features (rank, route seconds, local score, crowd, level gap,
   mob HP, dx, dy, bot on a rope, bot HP), each row weighted by one over its probability so explored picks
   count for the decisions they stand for. The features are the ones the Java scorer can compute (stage 3).
5. **Policy:** on each decision, the best scored candidate among those the logging policy could have taken.
   It only reorders near ties on fresh picks, so every choice it makes has logged support.
6. **Gate:** the latest 25% of decisions are held out. Self normalized inverse propensity replay estimates
   the value per decision for the model and for the rule; 1000 bootstrap resamples give a 90% interval on
   the gain. It passes only when the log meets the readiness counts (20,000 picks, 10 maps, 300 explored)
   and the interval's lower end is above zero.

## Output

`out/grind-target-<model>.json`: feature names, means, standard deviations, weights, bias, the readiness
counts, the held out evaluation, and `passes_gate`. Stage 3 loads it only when `passes_gate` is true.

## Limits

- One pick at a time: a pick also changes where the bot ends up for the next one, which this ignores.
- Linear: it can rank by weighted sums of features, not learn interactions. The trees can.
- The held out slice is the latest time, so a model that wins earlier and loses later reads as a loss.
