# Nav gate

Fails a branch that gets a bot stuck on a move that works on main, or makes a move land on another platform
than planned every time. Run it before merging any change to movement, navigation or the graph builder.

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
   stayed on its platform or froze, the trap that stalls a live bot for hours. So does a move that lands
   elsewhere on the branch while it works on the base, or, when its ends moved, has nothing near it on the base
   that fails too: the planner trusts it and the bot replans every time it takes it. A move that works on the
   base and has no move like it on the branch (any type, start within 48 px across, end within 64 px
   across, both within 10 px up or down) becomes a trip to probe: a change that prunes or reshapes
   edges can take away a way between two platforms without leaving anything stuck behind.
3. **Confirm.** The sim isn't fully deterministic: timer threads run beside the tick, so a move near the
   edge of working can pass one run and fail the next. Each candidate runs 10 times on each side. A
   regression fails at least 8 of 10 on the branch and at most 2 of 10 on the base, or doesn't exist on
   the base at all. Each trip also runs 10 times on each side (`BotEdgeSweepCli --probe`), but the bot gets
   only the destination and the live router picks the way, so a branch that still gets there by another
   path passes. A lost way fails at least 8 of 10 on the branch and at most 2 of 10 on the base.

## What it gates and what it doesn't

- **Fails on:** confirmed regressions (stuck, or landing elsewhere) and confirmed lost ways.
- **Reports only:** the total of moves landing elsewhere, including ones the base has too, and flaky moves
  that pass on a retry.
- **Can't see:** monsters, portals, ropes, teleports and flash jumps, or traps the sim escapes and a live
  bot doesn't. The stall detectors and the responder stay the backstop.

## Validation (2026-10-11)

- main against main: 2 candidates, both failing about as often on each side (8/10 and 8/10, 2/10 and
  3/10). PASS.
- The #25 era wall check put back: 95 candidates, 88 confirmed regressions on 28 maps. FAIL.

Lost ways (2026-10-12, 245 maps, 51,535 moves):

- main against main: 0 trips to probe. PASS.
- Walk off drops removed from the builder: 11,059 fewer moves and fewer stuck ones (12 to 5), so the
  stuck check alone passed it. 1,902 trips probed on 172 maps; the router found another way on almost all,
  and 6 on 5 maps were confirmed lost (Kerning City, Orbis, Eos Tower,
  Singapore CBD twice, the NLC subway). FAIL.
  Probing took about 4 minutes a side.

Landing elsewhere (2026-10-14, 253 maps):

- main against main: 4 candidates from sim noise, none confirmed. PASS.
- main with graph v77 reverted (walk off drops falling from the region end again): 227 candidates, 222
  confirmed, on the stairs and slopes v77 fixed. FAIL.

Tests for the comparison: `python3 -m unittest tools/navgate/test_compare_sweeps.py`.
