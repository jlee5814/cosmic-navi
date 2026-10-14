---
name: kb_bot_shop_cold_tick_stall
description: ROOT CAUSE of the 0.5 to 0.65 s tick-autopilot stall on the first bot after boot. The weapon upgrade search loaded every NPC shop from MySQL and parsed its weapons on the tick; fix = shops warm with the grind data, the search waits for the warm. Read before adding a shop or catalog scan to a bot tick.
type: project
---
# Cold shop catalog stalled a bot tick

- **Symptom:** once per boot, `Bot tick stall: <bot> took 514 ms phases=tick-autopilot=512ms` (640 ms another
  time), on whichever bot first ran the check after spawning. Later bots and later checks were fast.
- **How it was found:** JFR on the live server (`jcmd 1 JFR.start settings=profile`, spawn six bots cold,
  `jfr print --json --events jdk.ExecutionSample,jdk.NativeMethodSample`), then the samples of the stalled
  timer thread inside the stall window. All three were under `BotAutopilotManager.tick` >
  `BotShopManager.wantsWeaponUpgrade` > `computeWeaponUpgradeShopMap` > `nearestShopMapSelling`, in
  `ShopFactory.loadShop` (a MySQL query per shop) and `ItemInformationProvider.getEquipmentSlot` (a WZ XML
  parse per weapon). Execution samples alone showed one sample: the thread was mostly waiting on I/O, so
  read the native samples too.
- **Root cause:** `nearestShopMapSelling` walks every NPC shop (`SELECT DISTINCT npcid FROM shops`) and
  tests each one's weapons. `ShopFactory` and the item caches fill lazily, so the first search after boot
  paid for all of it on the game tick. Warm searches don't show up in the samples.
- **Fix:**
  - `BotShopManager.warmShopCatalog`, called from `BotGrindAdvisor.warmGrindData`, loads every shop and
    adds the equips they sell to the parallel equip warm (249 equips sold only in shops, beyond the drop
    catalog).
  - `computeWeaponUpgradeShopMap` runs the search only when `BotGrindAdvisor.isWarm()`; until then the check
    stays due and runs on a later tick (the quest scan uses the same gate).
  - `ShopFactory`'s public methods are `synchronized`: bot ticks on several timer threads and the warm read
    and fill its `HashMap`s, which hold null for an id with no shop.
  - `shop-weapon-search` perf label on the search.

## Rule

A scan over a whole catalog (shops, items, quests, maps) on a bot tick has to read caches the boot warm has
filled, and gate on `BotGrindAdvisor.isWarm()` until then. Never load from the database or parse WZ on a
tick.
