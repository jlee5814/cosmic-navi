---
name: kb_bot_fleet_ops_blind_spots
description: "Which bots BotFleetOps watches for stalls and which it skips on purpose; capped scroll hunters were skipped by mistake until Oct 18, so SipsBuddy26's 11 h Snowy Hill freeze went unseen; the dry hunt flag for a hunter that never gets its scroll"
---

`BotFleetOps` samples every online bot once a minute. A sample is **busy** (`sampleOf`) when the bot is grinding,
on autopilot or on an errand, and none of the exclusions below hold. Only busy stretches count toward the frozen
(still 10 to 20 min), no EXP (30 to 45 min) and low EXP (under a fifth of its usual pace for 30 min on one map)
checks, their whispers and auto rescues.

**Skipped on purpose** (not busy):
- An operator command is live (`operatorCmd != null`): the owner is driving.
- Logging out, on a break (`breakUntilMs`), or resting for HP (`restErrand`).
- Holding at its training cap (`BotTrainingPlan.holdsAtCap`): parked, not stalled. Storage mules are this.
- Waiting for or riding a ferry (`BotFerryManager.isWaitingOrRiding`): standing on the dock is the plan.

**Fixed Oct 18.** The cap exclusion was `!BotTrainingPlan.complete`, which also skipped capped bots hunting scrolls
for the fleet (#150), since they are complete too. SipsBuddy26 sat knocked back on Snowy Hill for 11 hours
([[kb_bot_en_route_attack_reach]]) and fleet ops never looked. Now the exclusion is `holdsAtCap`, the SSOT that
already knows a hunter isn't holding. A hunter (`Track.hunting`, from `entry.scrollHunt`) is judged by the frozen
and no EXP checks only: it picks maps by drop value, so a low EXP rate is its plan, and its pace sets no bar for
its level band (`bandMedian` skips it). Tests: `BotFleetOpsTest.aHunterIsJudgedByFreezesNotByItsExpPace`,
`huntersSetNoBarForTheirLevelBand`, and the busy asserts in the two capped tests of `BotAutopilotManagerTest`.

**Dry hunt (Oct 18).** A hunter that keeps killing but never gets a wanted scroll. `BotCombatManager.attackMonster`
counts each target its swing kills while on the hunt map (`BotEntry.huntKills`); a pickup of any scroll in
`huntWanted` (the set `BotScrollHunt.recommend` priced) restarts the count; a hunt on a new map starts it fresh
(`BotAutopilotManager.startHuntProgress`). Fleet ops whispers once per hunt (`dryHuntAlert`) when
(1 - p)^kills < 1/100 after at least an hour, with p the plan's per kill chance. That chance is value weighted
(`recommendFarmBasket` scales each scroll by its weight over the top weight), so it is a lower bound of the real
chance: the flag can come late, never early. It whispers instead of rescuing, since a scroll only sends the bot
back to the same farm. Likely causes when it fires: drop_data the server doesn't honor, a drop the bot can't
reach to loot, or kills of mobs that aren't the dropper. `navi why` and `/api/botdebug` `hunt` show the count.

**Still blind.** A farmer on a poor map that still earns EXP, and a ferry that never arrives (hidden by the
ferry exclusion). Neither has been seen live yet.
See [[kb_bot_fleet_time_loss]].
