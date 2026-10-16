---
name: kb_bot_support_leveling_stalls
description: "Why SipsBuddy2 (77 Priest) leveled at a tenth of SipsBuddy1's rate: a basic swing priced login plan, an unreachable best map, and boots bought but never worn. The three fixes and how to diagnose the next slow bot"
---

Found 2026-10-16 (#139, #140, #141). SipsBuddy2's own planner rates it at 450k to 600k exp an hour, about
SipsBuddy1's rate; the 48k reading came from where it was, not what it could do.

**1. Login plan priced by a basic swing (#141).** `estimateBestSkillHitDamage` reads the entry's attack skill
cache, first built on the bot's first AI tick (`BotManager` common tick, `rebuildSkillCacheIfNeeded`). A bot that
starts autopilot as it logs in decided before then, priced every mob by a wand swing, and went where a swing
kills: Murupa (level 3, 21,745 exp/h) or Nefarious Hill (48k exp/h) instead of Line 2 (463k). The plan held until
the next decide. `BotAutopilotManager.start`/`startParty` now warm the cache first. First plan after the fix:
Ghost Ship 2 (600k exp/h). SipsBuddy3 (55 Cleric) swapped between the same two maps for the same reason.

**2. Best map unreachable (#139).** Chimney Possessed by the Scarecrow (682010202, 586k exp/h) is entered only
from portal `in01` high in Mansion's Main Chimney (682010200); the climb from the bottom uses spring pads (portal
type 12, pcj), which the nav graph doesn't model. Travel's `canReach` correctly found no portal, the planner kept
picking the map, and the bot froze 20 minutes at a time until fleet ops scrolled it out. Now
`BotTravelManager.noWayFromHere` makes `onUnreachableTarget` avoid the map for 45 to 90 minutes and re-decide.
Avoids are in memory, so a restart costs one more trip. Springs remain open (see
[[kb_bot_map_partition_travel]]).

**3. Boots bought, never worn (#139).** The gear trip (`equipValue`, survival included) wanted Red Whitebottom
Boots (+13 WDEF); the optimizer scored damage then stat sum with no defense term, so they tied with the worn pair.
Defense now breaks damage ties ([[kb_bot_equip_optimizer]]).

**Not a cause.** LUK 7 locks all mage armor (every robe, hat, glove and shoe needs LUK 33 to 68, from WZ), so
SipsBuddy2 wears an Undershirt. But INT feeds total magic 1:1 and armor gives a few INT, so a lukless mage does
more damage; the AP build parks LUK at the floor on purpose. The priest SP build puts Holy Symbol before Shining
Ray (support role), which costs some kill speed but is the party buff.

**Diagnosing the next slow bot.**
1. `/api/apdebug?id=` section g: what the solo advisor picks now; compare with `/api/botdebug` `dst` and status.
2. A plan far below the advisor's pick: when was it made (login, during transit) and with what inputs.
3. `docker logs | grep <name>` for `auto rescued`, `responder`, `gear trip` repeats.
4. `/api/mapgraph?id=` for the stuck map: a region with no out edges and `coll` portals is a spring or a jump pad.
