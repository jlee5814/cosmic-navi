---
name: kb_bot_fleet_time_loss
description: "Where the contenders' online time goes (Oct 12 to 16): 57% grinding; travel, restarts and freezes split the rest. The freeze sites ranked, and the tool that measures it"
---

Measured 2026-10-16 with `tools/bots/fleet_time_loss.py` (logs only: grind target rows plus server logs; a bot is
grinding while its rows are at most 3 minutes apart, a 3 to 90 minute gap is online but not grinding, classified
by the bot's log lines inside it). Only the contenders and one Spearman had grind rows.

| bot | online h | grinding h | exp/h grinding | top losses (h) |
|---|---|---|---|---|
| SipsBuddy2 | 41.9 | 22.2 | 816k | travel 6.2, restarts 5.0, frozen 4.2, pauses 2.2 |
| SipsBuddy1 | 31.4 | 17.9 | 545k | travel 4.1, restarts 3.5, frozen 2.6, mule visits 2.4 |
| SipsBuddy26 | 14.8 | 10.1 | 169k | pauses 2.8, travel 1.5 |
| SipsBuddy3 | 10.7 | 5.8 | 375k | frozen 2.3, travel 1.5 |

Fleet: 98.8 h online, 56.0 h grinding (57%). Freezes and stalls flagged by fleet ops: 1,023 minutes.

**Freeze sites (fleet ops rescues, minutes at rescue time)**
- Haunted House, 291 min (SipsBuddy2 and 3, Oct 12 to 16): the Main Chimney spring pads
  ([[kb_bot_support_leveling_stalls]]). Avoid plus immediate re-decide since #139 and #143.
- Helios Tower 2nd and 99th floors, 320 min (SipsBuddy1, Oct 15 to 16): not reproduced. Six rescues on Oct 15,
  one on Oct 16 at 03:48, none since. On Oct 18 a Spearman rode the elevator up (2F to 99F in 20 s) and down
  (35 s) with no give up, so the ride itself works ([[kb_bot_eventmanager_transit_rides]]). A refused boarding
  (elevator moving) gives up the hop and retries 45 s later against a 48 s cycle at travel rate 5; that can
  delay a boarding but not by the hours seen. If it recurs, read `/api/botdebug` `lastHit` and `enRoute` on the
  stuck floor first: a mob in the lane ([[kb_bot_en_route_attack_reach]]) fits a bot that returns to the same spot.
- Ludibrium Ticketing Place and Ludibrium, 292 min (SipsBuddy2, Oct 13): the ferry ticket sell loop, fixed by #91
  ([[kb_bot_ferry_ticket_sell_loop]]).
- El Nath, 80 min (SipsBuddy1, Oct 14 to 15): slow then stalled with no return scroll; not diagnosed.
- Encounter with the Buddha 800020130 (SipsBuddy2, Oct 17 to 18, parked Oct 18): not a terrain trap (one flat
  floor). The grind target log (`logs/bot-decisions/grind-target-*.jsonl`) shows 20 picks of Black Crow 9400014
  (area boss, lv115, 35M HP) as the only candidate, held 16 to 83 min each for under 1% of its HP. Grind
  candidates now skip a boss whose solo kill time (`BotGrindAdvisor.killProfile`) is over 5 min, outside event
  instances (`BotCombatManager.isHopelessBossTarget`). For the next unexplained freeze on a grind map, grep the
  grind target log for long `holdMs` with a tiny `mobHpDrop` first.
- Snowy Hill, about 11 h (SipsBuddy26, Oct 17, after this window): the en route attack aimed past the Jr. Pepe in
  the lane and the bot was knocked back on every approach; fixed ([[kb_bot_en_route_attack_reach]]). Possibly the
  El Nath stall above too, since Snowy Hill is the only walking way out.

**Wrong map after login** was small in this window: SipsBuddy2 45 min (Murupa, Nefarious Hill, fixed by #141) and
SipsBuddy26 82 min on an Ellinia field (level 10 mobs at level 40, 71k vs 285k exp/h), not diagnosed.

**Server restarts** cost 9.5 h of the window (each restart: logout, login, travel back), most of it the many deploys
on Oct 16. Batch deploys.
