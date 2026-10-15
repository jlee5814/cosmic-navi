---
name: kb_bot_storage_mule_visits
description: "Storage mule visits: Free Market routing (scripted entrances, per bot exit), the drop handoff, the mule loot age exemption, and how the ETC shelf holds while a visit is due"
---

Built 2026-10-14 in `BotStorageMuleManager`; design and flow in `docs/bot/etc-storage-mules.md`.

**Free Market routing (910000000, `BotWorldGraph.FREE_MARKET`)**
- 22 towns enter through a scripted portal (script marketNN: saveLocation FREE_MARKET, warp to out00). Most are
  named market00; exceptions: Ereve 130000200 st00, Aquarium 230000000 market01, Magatia 261000000 market0.
  Henesys' portal is in Henesys Market 100000100, not 100000000.
- The only exit is out00 (script market00, back to the saved town, Henesys if none). So the exit is a per bot
  edge: `RouteOptions.fmReturn` = `Character.peekSavedLocation("FREE_MARKET")` when the bot stands in the
  market, else -1 (`BotAutopilotManager.freeMarketReturn`, passed at every RouteOptions site). Never a static
  edge, or the market becomes a free shortcut between towns. Same pattern as the Mushroom Shrine.
- `scriptedEntrancePortal(FREE_MARKET, town)` answers out00 for any town; the rooms 910000001 to 022 use plain
  portals.
- fieldLimit has no DROP_LIMIT, so drops work there; drops expire after 180 s, 200 per map.

**Handoff**
- A drop, not a trade: the mule (summoned by `spawnManagedBot` off the tick thread, on duty in `DUTIES`)
  stands there first, the farmer drops whole stacks in batches of 8 at most within 60 px, the mule's passive
  loot takes them. The farmer sets `lootInhibitMs` so it never picks its own handoff back up.
- A bot's own drop normally waits 15 s before another bot may target it
  (`BOT_INVENTORY_DROP_TARGET_LOOT_AGE_MS`); `BotLootEligibility.requiredTargetLootAgeMs` exempts the mule on
  duty for that partner (`isPartnerDrop`).
- In `BotManager.tickCore` a mule on duty skips the idle fast path and the park at cap (it is a capped roster
  bot standing still on purpose), and `tickMule` runs after the logout branch, before the autopilot.

**Maple gear (owner rule)**
- Never sold: `isMapleItem` (an equip whose name contains Maple, not a quest item) is checked in the equip sell
  collector and classifier (status BIN). Non-gear Maple items sell normally.
- `handoffItems` = ETC shelf + BIN equips; `roleFor` routes Maple gear by kind (`mapleBinRole`) and a material
  by family (`binRole`). The test reads `docs/bot/rosters/sipsbuddy.csv` to prove every bin role exists.
- A batch counts the mule's free slots per tab. The mule on duty skips autoEquip and loot offers.
- `refillBins`: when a visit's bins run out, it claims mules it still needs that came free (another farmer's
  visit held them), checking every 5 s, for up to 4 minutes after its last bin. `muleVisitServed` stops repeats.
- Tests must stub `BotInventoryManager.itemName` (and `questItem` for Maple names); the default reads WZ names.

**Equip hold value (replaces the never-sell line, 2026-10-15)**
- `BotInventoryManager.rankHold`: kept-for-value equips (past `shouldKeepForSellTrash`) group by item; copies rank
  by roll (`tradeValueScore`); the best `demand` copies are worth their obtain cost (`equipObtainCost`, the
  `scrollMarketValueMeso` SSOT: shop price, else farming effort), the rest 0 (SURPLUS, sells). Worth holding,
  best first, the top 24 stay (HOARD); the rest past the shelf sell (HLIM).
- Demand: `BotStorageMuleManager.fleetDemand`, the owner and the owner's bots (beginners and the holder itself
  excluded) who pass the player requirement check at level + 10 and haven't outgrown it by 30 levels.
  The roster snapshot (job, level) loads with the mule directory. `UNKNOWN_DEMAND` until it loads, so nothing
  sells on a guess. Tests stub `fleetDemand` and `equipObtainCost`.

**Shelf hold**
- `holdsShelf`: while a visit is active or due, the automatic cramped shop trip leaves the shelf alone
  (`shouldAutoSellTrash` ETC term and the sell escalation). An explicit sell order still sells it. Every visit
  end starts a 45 to 90 minute cooldown, which releases the hold, so a failed visit or a full bin falls back to
  selling.
- A mule found full is skipped for 6 hours (`FULL_BINS`). Nothing empties a bin yet.
