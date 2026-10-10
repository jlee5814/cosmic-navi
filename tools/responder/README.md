# Navi responder

A model looks at bot stalls the game's rules can't settle and picks one safe action.

Fleet ops in the game server (`BotFleetOps`) already rescues a frozen bot by itself after 20 minutes. A return scroll can't fix everything, though. An errand loop restarts after the scroll, a bot can earn nothing on an errand, and some spots refreeze a bot after two rescues. Those show up at `GET /api/fleetops/incidents`, and this script handles them.

| Incident | Meaning |
|---|---|
| `errand_loop` | A third errand to the same map within 45 minutes |
| `no_exp_on_errand` | Busy on an errand for 30 minutes or more with no EXP |
| `refreezing` | Frozen again after 2 auto rescues in 2 hours |
| `no_exp_after_rescue` | Still earning nothing after an auto rescue (or a rescue that found no scroll) |
| `low_exp_on_errand` | On an errand for 30 minutes, earning under a fifth of its usual EXP per hour |
| `low_exp_after_rescue` | Still under a fifth of its usual pace after an auto rescue |

For each new incident, one `claude -p` call (Sonnet on the owner's plan, no tools) reads the bot's state, bag, recent log lines and chat, and answers with one action from a fixed menu:

| Action | Effect, through the game's own code |
|---|---|
| `rescue` | Town return scroll, then back to its own plan |
| `hold_errands` | Drops the current errand and allows none for 15 to 60 minutes, so the bot grinds instead of riding a loop |
| `sell_trash` | A sell visit at a shop on its current map |
| `park` | Logs a roster bot out and keeps it out |
| `none` | Nothing on the menu helps |

The server runs the action on the bot's next tick and whispers the owner what it did and why. It caps each bot at 3 responder actions in 2 hours whatever the model says. When the model judges the cause a code bug, the script files a GitHub issue labeled `responder`, one per title. The action buys time; the issue gets the bug fixed.

The script calls a model only when an incident is open, at most 3 a run and 24 a day, and asks about the same bot and kind at most once an hour.

## Run

```sh
python3 tools/responder/responder.py --dry-run          # ask, but neither act nor file issues
python3 tools/responder/responder.py --dry-run --incidents tmp/replay.json --runs /tmp/replay
tools/responder/install_responder_agent.sh             # every 5 minutes, live
tools/responder/install_responder_agent.sh --dry-run   # every 5 minutes, decisions logged only
```

Every decision goes to `runs/log.jsonl` with the incident, the model's diagnosis, the action, the server's answer and the model's latency. The LaunchAgent copy lives in `~/Library/Application Support/cosmic-responder`.

## Tests

```sh
python3 -m unittest tools/responder/test_responder.py
```

Java side: `BotFleetOpsTest` covers which stalls become incidents and how `hold_errands` works.
