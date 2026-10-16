---
name: kb_bot_boss_plan
description: "Boss run planner for a bot party (/api/bossplan): where boss sites come from, the fight, survival and payback rules, and the first read for SipsBuddy1 to 3 (no run pays back yet)"
---

Built 2026-10-16 (`BotBossPlanReport`, #134, #135, and the touch loss follow up); route in `docs/bot/web-endpoints.md`.

**Where bosses live**
- Map.wz life: boss flagged, respawning (`mobTime` != -1), on a non town, non instanced field. `mobTime` is seconds
  from the kill to the respawn (`SpawnPoint`), read per site by `BotSpawnIndex.mobTimeSeconds`.
- Area bosses (Mano, Stumpy, Faust, Jr. Balrog in the Sanctuary, Timer, Eliza and the rest) are NOT in Map.wz: each
  `scripts/event/AreaBoss*.js` spawns one monster on one map and rechecks every 3 hours, on every channel
  (`Channel` loads all of `scripts/event`). `scriptSite` parses map, monster and period. So the gear plan's farm
  route (`farmableDropper`, spawn index only) misses every area boss drop.
- Summoned bosses (Zakum, Horntail, Pink Bean) and quest one shots (Nex, Spirit of Rock) don't appear: no field site.

**The rules**
- Fighter: reaches the boss (world flood), hurts it (`BotGrindAdvisor.killProfile`, the grind kill model) and lives
  through two landed hardest touches (`physicalTouchDamageAt` at the top roll vs `effectiveMaxHp`). Bots take touch
  damage only, so boss skills and magic attacks aren't modeled.
- Run: longest fighter trip plus HP over summed damage. Cost is each fighter's grind exp per hour times the run.
  Boss exp is split by damage share.
- Drop: equips a fighter can wear now (`BotGearPlanReport.wearableNow`), valued for the fighter who gains most by
  `BotGearPayback.judge` over 1/chance runs, net of the exp the runs earn. Wear is cut by the calendar wait
  (runs × max(run, respawn) minus run time), since the bot levels while it waits.

**First read (2026-10-16, SipsBuddy1 67 IL 532k exp/h, SipsBuddy2 77 Priest 48k exp/h, SipsBuddy3 55 Cleric 24k exp/h)**
- No boss run pays back. On exp, area bosses return a few thousand per kill against 50k to 1.1M forgone
  (Bamboo Warrior 34k vs 335k). On drops, boss gear drops at about 1.6% need about 62 runs; at a 1 to 3 hour respawn
  that's 3 to 9 days, which outlasts the piece's wear.
- What blocks the bigger bosses is HP and damage: Headless Horseman needs about 6,200 HP each (hardest touch 3,100)
  and the party does about 200 damage a second on 3.5M HP; Bigfoot 24,500 HP, Black Crow 17,000, Male Boss 9,600,
  Leviathan 6,600. SipsBuddy1 needs about 3,500 HP for Jr. Balrog, Snowman and Eliza (has 2,656 without Magic Guard,
  3,421 with). SipsBuddy3 (Cleric) deals single digits to most bosses.
- Snow Witch and Riche have avoid 999, and Headless Horseman and Blue Mushmom have 800 class defenses, so a party
  does under 20 damage a second there: correct, not a bug.
- A one off: the first read showed Headless Horseman worth it for SipsBuddy2 alone (5 hour kill, 600k exp vs 239k at
  48k/h). SipsBuddy2's HP then read 6,030 vs the 6,201 needed, so it dropped out. HP depends on gear and buffs at
  read time.

Related: [[kb_bot_grind_gear_valuation]] (payback and wear), [[kb_bot_craft_on_demand]].
