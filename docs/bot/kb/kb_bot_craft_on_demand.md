---
name: kb_bot_craft_on_demand
description: "Crafting on demand (BotCraftManager): the NPC recipe export, the refine chain planner, the upgrade and cost gates, the bin pickup at the Free Market, and the craft errand at the NPC"
---

Built 2026-10-15 in `BotCraftManager` (phase A: the bot's own upgrade from its own bag).

**Recipes**
- `tools/bots/gen_npc_recipes.py` exports every scripts/npc recipe table (itemSet, matSet, matQtySet, costSet) to
  `src/main/resources/bots/npc-recipes.csv`: `npcId,outputId,fee,mat:qty;...,yield`. 651 recipes, 524 of them
  gear. Yield is what one craft gives (screws 15, arrows by id); placeholder entries (ids below 1000000) are
  skipped. Rerun the script when scripts change.
- The crafting parts list (`BotInventoryManager.isCraftingPart`) is derived from the same file, so there is one
  recipe source.

**Planning (`bestPlan`, `plan`, `resolve`)**
- Gear is scored by the gear SSOT, `BotScrollManager.potentialValue` (base value plus half the best catalog
  scroll EV per open slot), against the same for the worn piece, as the shop's gear purchase does. A recipe
  qualifies when that gain is positive, the output is wearable now and of the preferred weapon type. (Until
  2026-10-16 it had its own offense only scorer and a 20 level window; that parallel scorer is gone.)
- Scroll bases: when the clean output loses to the worn piece as is (`equipValue`) but wins on potential, the
  plan is a scroll base (Work Gloves: 15 Leather, five open slots). Only bots with `selfScrollEnabled` (on for
  population bots) craft one; the town break scroll pass then scrolls it and autoEquip wears it once it wins.
- The search runs on `BotGrindAdvisor.DECIDE_POOL` (perf key `craft-scan`) and starts the errand back on the
  scheduler thread, after rechecking the bot is free.
- `resolve` takes materials from the bag and crafts any shortfall from ETC recipes (ore to plate, plate to
  screw), up to 3 levels deep, preferring the parent recipe's NPC so one stop does both, and backtracking
  across alternative recipes. Steps come out in run order, the goal last.
- Cost gates: fees at most half the bot's meso, and fees plus the sell value of the inputs below the item's
  obtain cost (`scrollMarketValueMeso`: shop price, else farm cost).
- Checked every 10 to 20 minutes per bot off `maybeStart`; skipped while following or on any other errand.

**Errand**
- A `DetourErrand` after the mule visit. Each step's NPC map is the nearest with a route
  (`BotSpawnIndex.mapsWithNpc`); travel and approach use the shared `BotTravelManager.tickApproachNpc`, a
  talk dwell (`npcDwellReady`) per step, and `ErrandProgress` with a 4 minute no progress stall.
- `craft` does what the script does via `AbstractPlayerInteraction`: haveItem and canHold checks,
  `gainItem(mat, -qty)`, `gainMeso(-fee)`, `gainItem(out, n)` (gear one at a time, random stats). After the
  goal it runs `autoEquip`.

**Bin pickup (phase B, 2026-10-15)**
- Plans count the farmer's free bins (`BotStorageMuleManager.binStock`, the roster snapshot's mule ETC tabs).
  `Plan.used` is what the steps take from holdings; intermediates an earlier step makes are tracked apart
  (`Acc.made`) so they never count as a pickup. `shortfall(used, bag)` is the pickup, split by `assignPickups`.
- The errand starts at the Free Market: mules on a pickup duty (`Duty.give`, null on a visit) drop their share
  by the farmer, which loots it; then `nextStop` to the NPC. Flow in `docs/bot/etc-storage-mules.md`.
- The snapshot is up to 30 minutes old, so a bin can come up short; the farmer then replans from its bag.
- Not yet: crafting for other fleet members (phase C).

**Debugging: `/api/craftplan?id=`** (2026-10-15)
- Runs the errand's own `choose` live and returns the plan, pickups, rejected counts by reason and the 10
  nearest upgrades with why each lost (`bestPlan`'s `Rejects` collector).
- First live read, SipsBuddy1 (64 IL) and SipsBuddy2 (77 Priest), 24 bins counted: no plan; every upgrade lost
  on materials. Their level gloves and shoes need Dragon Skin (4000030) 50 to 70, Star Rock or Moon Rock, or a
  base glove (gear isn't crafted as an input). Screws and plates are not the blocker; they refine from ore.

**Fleet material demand: `/api/craftdemand`** (2026-10-16)
- `BotCraftManager.wants`: the recipes `bestPlan` rejects as short on materials (every other gate passed), one
  per output, each replanned as if every raw input (`RAW`: inputs no refining recipe makes, so ores, drops,
  base gear) were at hand; the shortfall against bag plus bins is what it lacks.
- The endpoint sums those across online bots and ranks by `aloneGain`, the gain of upgrades the item is the
  last missing piece of (what farming it alone unlocks), then by total gain.
