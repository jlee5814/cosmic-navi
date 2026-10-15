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

**Shelf hold**
- `holdsShelf`: while a visit is active or due, the automatic cramped shop trip leaves the shelf alone
  (`shouldAutoSellTrash` ETC term and the sell escalation). An explicit sell order still sells it. Every visit
  end starts a 45 to 90 minute cooldown, which releases the hold, so a failed visit or a full bin falls back to
  selling.
- A mule found full is skipped for 6 hours (`FULL_BINS`). Nothing empties a bin yet.
