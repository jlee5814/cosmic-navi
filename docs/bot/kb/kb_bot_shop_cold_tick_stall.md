---
name: kb_bot_shop_cold_tick_stall
description: ROOT CAUSES of the 0.4 to 1 s tick-autopilot stalls after boot. Weapon upgrade search loading every shop from MySQL, partition routing loading whole maps, and NPC approach points running one A* per candidate, all on the tick. How they were found (JFR) and fixed. Read before adding a catalog scan, a map load or a search loop to a bot tick.
type: project
---
# Tick stalls after boot: three causes on the autopilot tick

Symptom: `Bot tick stall: <bot> took 400 to 990 ms phases=tick-autopilot=...` within minutes of a boot, a few
times per boot, worst after a graph version bump. Each cause below was found the same way.

## How to find the code behind a stall line

1. `docker exec <container> jcmd 1 JFR.start name=s settings=profile filename=/tmp/s.jfr`, restart cold first.
2. Spawn bots (`/api/spawnbot?id=...`), wait for the stall line; note its thread (`TimerManager-Worker-N`)
   and time.
3. `jcmd 1 JFR.dump name=s filename=/tmp/s.jfr`, then
   `jfr print --json --events jdk.ExecutionSample,jdk.NativeMethodSample --stack-depth 64 /tmp/s.jfr`.
4. Keep the samples of that thread in the window `[time - took, time]` and count frames. Read the native
   samples too: a thread waiting on MySQL or a file shows almost no execution samples.

## 1. Weapon upgrade search loaded every shop (500 to 640 ms, first search after boot)

- `BotAutopilotManager.tick` > `BotShopManager.wantsWeaponUpgrade` > `computeWeaponUpgradeShopMap` >
  `nearestShopMapSelling` walks every NPC shop and tests each one's weapons. `ShopFactory` and the item
  caches fill lazily, so the first search paid a MySQL query per shop and a WZ parse per weapon.
- Fix: `BotShopManager.warmShopCatalog`, called from `BotGrindAdvisor.warmGrindData`, loads every shop and
  adds the equips they sell to the parallel equip warm (249 beyond the drop catalog). The search runs only
  when `BotGrindAdvisor.isWarm()`. `ShopFactory`'s public methods are `synchronized` (bot ticks on several
  timer threads and the warm fill its `HashMap`s, which hold null for an id with no shop). Perf label
  `shop-weapon-search`: 5 to 11 ms once warm.

## 2. Partition routing loaded whole maps (988 ms)

- `BotTravelManager.tickTravel` > `BotWorldPartitionRouter.route` searches outward through every map it
  reaches and asks `BotMapPartitionProvider.forMapId` for each. A map with no cached partition was loaded
  with `MapManager.getMap`: a WZ parse that also spawns the map's monsters, on the tick. Partitions persist
  per graph version (`cache/bot-partition/v<N>/`), so each version bump starts cold.
- Fix: `forMapId` returns the cached partition, or derives it from a map already in memory; otherwise it
  queues the map (once) on the `bot-partition-derive` thread, which builds the partition from
  `BotNavigationMapLoader.loadMapGeometry` (no monsters) and returns null. Null is a dead end, so the caller
  uses the map level route until the partition lands.

## 3. NPC approach points ran one A* per candidate (441 ms in Ellinia)

- A quest errand walking to an NPC: `BotTravelManager.pickReachableApproachPoint` > `approachCandidates`
  sampled up to 21 points per foothold in range and ran a walk-only A* to each.
- Fix: `BotNavigationGraph.canReach(start, target, 0)`, the reachability source of truth, one cached BFS
  per start region. `BotApproachReachabilityTest` checks it agrees with the walk-only search for every
  region pair from every fifth start region in Ellinia.

## Result

Cold restart, 16 bots on 15 maps, 5 minutes: no tick-autopilot stall. One 308 ms `tick-common-systems` stall
4 s after spawn, before the warm finished: `BotBuildManager.checkLevelUp` > `resolveApBuild` >
`BotEquipManager.collectAutoEquipCandidates` > `ItemInformationProvider.getEquipLevelReq`, a cold WZ parse of
the bot's own gear, which the warm does not cover. Left open.

## Rule

A bot tick never loads from MySQL, never parses WZ, never instantiates a map, and never runs one search
per candidate. Read caches the boot warm fills (gate on `BotGrindAdvisor.isWarm()`), queue the load on a
background thread, or use a cached answer such as `canReach`.
