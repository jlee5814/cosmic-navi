---
name: kb_bot_area_boss_maps
description: "Which grind maps hold a boss a bot can't kill alone, from a WZ scan (tools/wz/grind_boss_scan.py) joined with the grind decision log, Oct 20. Two have caught fleet bots: Encounter with the Buddha and Phantom Forest Crossroads. The guards are map agnostic (#177, #189, #196)"
---

**Why it matters.** `BotGrindAdvisor.profileFor` leaves bosses out of a map's rating, so a map with a 35M HP boss
on its floor rates the same as one without. In play the boss absorbs every forward shot aimed past it and blocks
the mobs behind it. The guards: #177 drops a hopeless boss as a pick (`isHopelessBossTarget`, solo kill over
300 s), #189 refuses a plan whose shot it would absorb and drops a target whose HP never falls, #196 drops picks
standing behind one on the bot's line.

**Scan, Oct 20.** `python3 tools/wz/grind_boss_scan.py --min-hp 1000000` from the repo root on the Mac. 63 boss
spawns sit on 51 grindable maps (non town, below 900000000, at least 3 grindable spawns). Most are small (Shade 30k,
Riche 11k, Snow Witch 7.7k) and are just a kill. The hopeless ones for the fleet's levels:

| Map | Boss | HP | Respawn | Grind mobs | Fleet picks |
|---|---|---|---|---|---|
| 800020130 Encounter with the Buddha | Black Crow | 35M | 23 h | lv 100 | 5015 (2 bots); froze SipsBuddy2 hourly |
| 610010201 Phantom Forest Crossroads | Headless Horseman | 3.5M | 6 h | lv 50 to 70 | 2 (SipsBuddy3, Oct 14): second pick held 277 s, mob HP never moved |
| 6100101xx Twisted Paths, 610010012/013/005 | Bigfoot | 32M | 12 h | lv 35 to 95 | 0 |
| 610010010/011/013/200/202, 682000001 | Headless Horseman | 3.5M | 6 h | lv 35 to 95 | 0 |
| 240020101/102 Griffey Forest, 240020401/402 Manon's Forest | Griffey, Manon | 3.7M | 0.5 to 3 h | lv 88 | 0 |
| 261020200/400/600 Alcadno Lab | Rurumo | 7.6M | 40 min | lv 42 to 58 | 0 |
| 250020300 Practice Field Advanced | Master Dummy | 15M | 40 min | lv 51 | 0 |
| 801040003 Parlor | Female Boss | 75M | 4 h | lv 62 to 72 | 0 |

Time Lane (270xxxxxx) and the Treasure Dungeon (674030300) have them too, above the fleet's levels.

**Reading it.** The `line L/R` columns count grindable spawns within 50 px of the boss's y on each side: the mobs a
bot on the far side loses. Phantom Forest is the next likely trap, since its lv 35 to 95 mobs suit most of the
fleet. Rerun the scan when a new level band opens or a map shows up in fleet ops stalls.
