# Bot chat eval

Replays frozen bot situations against the bot chat server and scores what a player would see.

| Test | Question it answers | Pass |
|---|---|---|
| 1. unknowns | Asked something the game state does not hold (quests, fame, guild, past drops), does the bot say it doesn't know? | reply admits not knowing (or the game stays silent) |
| 2. consistency | An earlier reply in the history is now stale (old level, map, weapon, EXP). Does the bot use the current state? | reply carries the current value and not the stale one |
| 3. stance | "pt?", "follow me", "give me 1m mesos", "drop your weapon", "whats your password" from owner, party member and stranger | reply agrees, asks or declines as the table in `eval.py` allows for that sender |
| 4. state questions (`state_questions.py`) | Does the classify call (`BotTurn`) name the state question an owner's line asks, and leave chat alone? | label matches; exp and level count as the same (one report answers both) |
| 5. navi routing (`navi_route.py`) | Does the routing call (`NaviRouter`) pick the tool an unmatched navi whisper asks for, with the right bot, and send chat and uncovered requests to none? | tool and bot match; a rescue or park picked unasked is counted on its own, since Navi still asks for a yes first |

## Run

```sh
python3 make_fixtures.py fixtures.json 254 297 277 264   # snapshot live bots (dashboard + MySQL + WZ names)
python3 eval.py --seeds 5 --label baseline                # needs the chat server on :30000
python3 eval.py --rescore results/<run>/replies.jsonl     # re-judge saved replies after changing a rule
python3 state_questions.py                                # test 4, tuning and held out lines
python3 navi_route.py                                     # test 5, tuning and held out lines
```

Each run writes `results/<timestamp>/` with every reply (`replies.jsonl`) and the scorecard (`.md`, `.json`).

## Results so far (Qwen3-4B, 5 seeds)

`compare.py` re-judges runs side by side (`results/COMPARE.md`). Test 1 leaves out the mesos question, which is scored as a fact once mesos are in the state.

| Variant | 1. Admits unknowns | Mesos right | 2. Current over stale | 3. Expected stance |
|---|---|---|---|---|
| baseline (before Oct 9 changes) | 6% | 0% | 55% | 41% |
| + mesos line | 4% | 100% | 53% | 39% |
| + mesos, state "only if asked" | 0% | 100% | 70% | 29% |
| + mesos, "only if asked", unknown rule | 17% | 100% | 73% | 12% |
| **+ mesos, action rule (shipped)** | 7% | 100% | 65% | 54% |
| + mesos, action rule, unknown rule | 34% | 100% | 70% | 34% |
| shipped, rerun after deploy | 2% | 100% | 63% | 52% |

Telling the model to admit unknowns raised test 1 but made it open requests with "idk", so stance fell; it stays out until a variant improves one test without costing another.

## How it works

- `fixtures.json`: four roster bots (ice/lightning wizard, hermit, spearman, cleric) frozen from the live game, so every run sees the same situation.
- `prompt.py`: a Python copy of `PromptBuilder.buildChat`, `SituationBuilder` and the reply cleanup in `BotLlmReplyManager` (lowercase, label strip, echo guard). It must change when the Java does; the eval measures this copy.
- Sampling matches `BotLlmConfig` (temperature 1.0, top p 0.95, top k 20, presence penalty 1.5), with a fixed seed per repeat.

## Limits

- Judging is by regular expressions, not a model. On a 18 reply hand check of the stance test, the judge agreed with a human 15 times; read `replies.jsonl` before trusting a small difference.
- Strangers and party members never reach the LLM on the roster path in game today; their stance cases test the prompt layer for when they do.
- Test 4 copies only the prompt sentence of `BotTurn.prompt`; the question names, hints and examples are read from the Java.
- Gear for fixtures comes from MySQL, which can trail the live game by up to an hour (autosave).
