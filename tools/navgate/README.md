# Nav gate

Fails a branch that gets a bot stuck on a move that works on main. Run it before merging any change to
movement, navigation or the graph builder.

```sh
tools/navgate/nav_gate.sh            # compare the working tree with origin/main
tools/navgate/nav_gate.sh <ref>      # or with another base
```

## How it works

1. **Sweep.** `BotEdgeSweepCli` runs every walk, jump and drop on every map bots have visited (from the
   EXP log, plus any ids in `tools/navgate/maps.txt`) through live physics, once for the base and once
   for the working tree. Both use this branch's sweep tool, so only the game code differs. The base sweep
   is cached per commit and tool version in `tmp/navgate/`, so a rerun sweeps only the branch (about 6
   minutes).
2. **Compare.** A move stuck on the branch but not on the base becomes a candidate. Stuck means the bot
   stayed on its platform or froze, the trap that stalls a live bot for hours.
3. **Confirm.** The sim isn't fully deterministic: timer threads run beside the tick, so a move near the
   edge of working can pass one run and fail the next. Each candidate runs 10 times on each side. A
   regression fails at least 8 of 10 on the branch and at most 2 of 10 on the base, or doesn't exist on
   the base at all.

## What it gates and what it doesn't

- **Fails on:** confirmed regressions.
- **Reports only:** moves that land on another platform than planned (the bot replans, so these cost time
  rather than freeze it) and flaky moves that pass on a retry.
- **Can't see:** monsters, portals, ropes, teleports and flash jumps, or traps the sim escapes and a live
  bot doesn't. The stall detectors and the responder stay the backstop.

## Validation (2026-10-11)

- main against main: 2 candidates, both failing about as often on each side (8/10 and 8/10, 2/10 and
  3/10). PASS.
- The #25 era wall check put back: 95 candidates, 88 confirmed regressions on 28 maps. FAIL.
