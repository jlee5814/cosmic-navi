# Bot web endpoints

Served by `server.bots.BotWorldGraphWebServer` (started from `Server` boot). Bound to all
interfaces on **port 8089**, **no auth** — exposes online player/bot names + locations to anyone on
the LAN (accepted: private game-server LAN). Open `http://<server-lan-ip>:8089/`.

> **Keep this in sync.** The `createContext(...)` list in `BotWorldGraphWebServer.start()` is the
> SSOT for routes. When you add/change/remove a route or its JSON shape, update this file. Exact
> field sets live in the code (`charJson`, `mapInfoJson`, `botDebugJson`, `serveCommand`); this doc is
> the map, not the schema of record.

## Pages

| Route | Method | Purpose |
|-------|--------|---------|
| `/` | GET | Legacy bot world-graph page (`botworld.html`). |
| `/map` | GET | RTS world map (`worldmap.html`): graph over WorldMap continent images, live positions, RTS control, collapsible per-map detail. |
| `/admin` | GET | Admin/settings menu (`admin.html`): edit bot config live, drive the population scheduler + LLM toggle, danger zone (disconnect-all / wipe). Front end for `/api/settings`. |
| `/mapgraph?id=<mapId>` | GET | Single-map nav-graph preview (`mapgraph.html`): regions (footholds/ropes), typed edges, NPCs, portals, live characters. Click a region for its `!pos` report; the **Pathfind** button lets you pick a source then target region to preview + verdict the route (`/api/pathfind`). **`show:` view toggles** (all client-side, all default OFF): `partition` colours regions by reachability component (SCC over the walk-only edge set — the same adjacency `BotNavigationGraph.canReach` uses; one colour = fully connected, multiple = a split map); `downjumps` (DROP edges), `jump-downs` (JUMP landing lower), `fj-downs` (FLASH_JUMP landing lower) show those normally-hidden down-edge classes. Opened from a world-map node's detail-panel button or by double-clicking a node (same tab), or by middle-clicking it (new tab); for a spot that merges several maps it opens the map someone is on, players first. |
| `/wm/{worldmapId}.png` | GET | A WorldMap continent background image from `wz-WorldMap/`; `/wm/overview.png` is the Maple World overview the map page opens on. |

## Read APIs (JSON, GET)

### `/api/worldmaps`
The world graph laid out over the WorldMap images: per worldmap `{id, name?, x, y, scale, nodes[], edges}`
(`name` = the region name from the game's MapLink tooltips, e.g. `Victoria Island`; absent when no link names it);
each node `{id, maps[], names[], x, y, hub, anchor, dup, danger, leaf, unreachable}`. A node may merge
several maps (`maps[]`). Drives the map page. (See `serveWorldMaps`/`worldmapsJson` for exact fields.)

### `/api/live`
Live occupancy of every online character, bucketed by map. Cached ~750 ms.
```
{"maps":{"<mapId>":{
   "players":[{"id","n","l","j","c","p","g"}, ...],
   "bots":[{"id","n","l","j","c","p","g","a","status"}, ...]
}}}
```
`n`=name, `l`=level, `j`=job, `c`=commandable (1 = managed/RTS-controllable bot), `p`=party id (0=none),
`g`=crew id (0=none). `a`=coarse activity bucket (`"grind"`|`"break"`|`"chill"`; chill = whole-session
chill login, gacha/idle/logging-off → break, resupply/travel/quest → grind) for the roster tally (bots
only). `status`=the
@botstatus line (bots only; drives the roster-hover tooltip and the right-panel detail — omitted for
players and for bots with no registry entry).

### `/api/mapinfo?id=<mapId>`
On-demand detail for one map.
```
{"mobs":[{"name","level","spawns"}, ...],     // spawns = WZ spawn-point count
 "bots":[{"name","status"}, ...],             // status = the @botstatus line
 "chat":[{"t","n","m"}, ...]}                 // recent map chat: time, name, message
```

### `/api/mapgraph?id=<mapId>[&sp=<>&jmp=<>&snow=0|1]`
One map's bot {@code BotNavigationGraph} + live features for the `/mapgraph` canvas. Loads the map on
demand (map factory builds it from WZ — footholds/portals/NPCs included) and blocks once on the cached
graph build, so an empty map still resolves. `sp`/`jmp`/`snow` pick which cached movement-profile graph
to render (default speed100/jump100 `BotMovementProfile.base()`); `active` echoes the served profile and
`profiles` lists the profiles currently cached for this map (the page's graph picker). `portals.k`
classifies each portal: `in` = shortcut whose target is this same map, `cross` = press-up portal to
another map, `coll` = a collision-warp portal type (WZ `pt` ∈ {3,9,12,13}). `edges` are de-duped to one
per (from-region, to-region, type), each carrying its `fromR`/`toR` region ids so the page can list a
selected region's edges (hover a listed edge to highlight it on the canvas). Each edge also carries `cost`,
`lsx` (launchStepX) and `n` = how many parallel launch-x edges collapsed into this one drawn line (`n>1` —
common around ropes — is why the raw `/api/pathfind` `explored` overlay shows more edges than are drawn).
Every drawn edge is **clickable** for its details (type, regions, cost, lsx, endpoints, parallel count).
Each region's `report` is the **same** debug text the `!pos` command prints (SSOT:
`BotNavigationDebugOverlay.describeRegion`); the page shows it when a region is clicked. The page also
renders top-bar composition stats (region counts split fh/rope, edge counts split by type), computed
client-side from `regions`+`edges`.
```
{"map","name","active":{sp,jmp,snow},"profiles":[{sp,jmp,snow},...],
 "bounds":{minX,minY,maxX,maxY},
 "regions":[{"id","kind":"fh","segs":[[x1,y1,x2,y2],...],"report":[lines]}   // foothold region: segment lines
          | {"id","kind":"rope","ladder","x","y1","y2","report":[lines]}],   // rope/ladder region: vertical span
 "edges":[{"t":"WALK|JUMP|DROP|CLIMB|PORTAL|TELEPORT|FLASH_JUMP","fromR","toR","cost","lsx","n","fx","fy","tx","ty"}, ...],
 "npcs":[{"x","y","n"}, ...],
 "portals":[{"x","y","k":"in|cross|coll","tm","n"}, ...],
 "chars":[{"x","y","n","bot"}, ...]}                             // live player+bot positions
```
The client renders regions/edges/npcs/portals once and repaints only `chars` on its 2 s poll. The same
region report backs the GM command **`!pos`** (`PosCommand` → `BotNavigationDebugOverlay.posReport`),
which reports the region you're standing on using your own movement profile.

### `/api/apdebug?id=<botCharId>[&maps=<mapId>,<mapId>]`
Read-only plain text: the bot's full autopilot grind decision report (reachable maps, per candidate exp/h,
gear value, travel and quest weights, the pick), the same report the in game `autopilot why` command and
the ops console `grind` verb write to `logs/bot-grind/ap-debug-<name>.txt`. Scores candidates without
applying a plan. `maps=` appends the raw candidate inputs for those maps (mob, kill time, spawns,
kills and exp per hour, travel weight), which the top 15 list hides. For a bot that decides solo, section g samples
the solo advisor it actually runs (wanderlust travel weight, danger weight, crowd surcharge, the exp and gear blend)
five times: map, mob, exp/h, needGear, gearFocused, wanted gear, score. Runs on the decide pool, so it can
take a few seconds.

### `/api/botdebug[?id=<botCharId>]`
Read-only per-bot autopilot internals for live debugging (party cohesion, follow, travel). No cache.
Every row also includes `plannedJob` (terminal owner career goal, or empty), `trainingTarget`
(0 means uncapped farming), `role` (assigned owner roster role), and `trainingComplete`.
`?id=` filters to one bot and adds a `detail` block (live stats + learned skills). **Read this over a DB
`skills`/`characters` query** — the in-memory `Character` is the SSOT; the DB row lags until the next save.
```
{"bots":[{
  "id","n","map","lvl",
  "party","crew","owner",          // owner: "null" | "self" | <human name>
  "apParty","dst","errand",        // apParty = party-autopilot on; dst = travel target map; errand = resupply map (-1 none)
  "grinding","following","followTo","transit","waiting",
  "op",                            // operator override command name ("" = none)
  "wt","atk","aoe","noAmmo",       // combat-readiness: weapon type; resolved single-target/aoe skill ids (atk=0 => no offensive skill => basic swing only); ammo gate
  "status",                        // the @botstatus line
  "detail":{                       // only when ?id= given
    "job","str","dex","int","luk","watk","matk",   // totals (base+equip)
    "hp","maxHp","mp","maxMp","exp","meso",
    "atkSkill","aoeSkill",         // resolved attack choices (BotCombatManager.rebuildSkillCacheIfNeeded; 0 = none)
    "pos":[x,y],"navEdge",         // live position + committed nav edge summary (e.g. "TELEPORT r3->r7 (..)->(..)")
    "navDecision","edgeBlock",     // last resolveTarget decision ("exec"/"skill-hop"/"reuse"/..) + last edge block reason ("tele-mp"/"fj-pos"/..)
    "canMoveSkill":bool,           // passes the teleport/flash-jump gate right now (has skill && >40% MP && >500k meso)
    "lastHit":{"mob","x","y","agoMs"},             // last mob touch that hit the bot: mob id, where it stood, ms ago (-1 never)
    "enRoute","enRouteTarget",     // last en route attack decision (attacked | out-of-range | move-window | not-worth |
                                   //   no-plan | no-target | idle-leech | in-air | climbing | quiet-move | no-ammo) + its target "mobId@x,y"
    "idleLeech":bool,              // party level-gap idle leech (suppresses all damage)
    "hunt":{"map","kills","sinceMs","chance","item"},  // scroll hunt progress (map -1 = not hunting): kills on the hunt map
                                   //   since the hunt or its last wanted scroll, the plan's per kill chance, its named scroll
    "nearMobs":[{"id","x","y","hp","v"}, ...],     // live mobs within 500 px; v = en route attack verdict:
                                   //   attackable | not-worth | no-plan | not-local | not-hostile
    "skills":{ "<skillId>": <level>, ... }          // every learned skill, live
  }
}, ...],
"routeCache":{"hits","misses","rate"}}             // region-route cache effectiveness, cumulative since server start (rate = hits/(hits+misses)); A/B vs pathfind count in bot-perf CSV
```

### `/api/bot/pathlog?id=<botCharId>`
On-demand per-bot navigation trace, mirroring the `!botnav pathlog <name>` command. **Toggle:** the
first call attaches a 120-tick (~6 s) ring-buffer recorder (`BotEntry.pathLogger`) — recording is
otherwise OFF, zero per-tick overhead; the second call detaches it, dumps the trace to `logs/bot-nav`,
and returns the report. Use briefly to capture why a bot is stuck.
```
1st call -> {"recording":true,"bot":<name>,"msg":"recording started — call again to dump"}
2nd call -> {"recording":false,"bot":<name>,"file":<path>,"report":<full pathlog text>}
```

### `/api/perf[?on=1|0][&durationMs=5000]`
Live performance snapshot from `BotPerformanceMonitor` (per-subsystem timings, including `scroll-scan`,
grind decides, movement, pathfind). Monitoring is opt-in/off by default: `?on=1` enables it, `?on=0`
disables, no param just reports the current aggregate. `durationMs` resets the monitor, samples that
many milliseconds (capped at 60000), and returns a bounded window sorted by total CPU time; use this for
repeatable live checks instead of comparing cumulative snapshots. Enable it, let it run, then read to see
what's hot, or call `durationMs` for a one-shot sample.
```
{"enabled":true,"sampleMs":5001,"processCpuMs":123,"processCore":0.025,
 "heapUsedBytes":123,"heapTotalBytes":123,"heapMaxBytes":123,"heapDeltaBytes":123,
 "sections":[{"section":"tick-total","count":N,"totalMs":..,"avgMs":..,"maxMs":..,
              "cpuMsPerSec":..,"core":..,"callsPerSec":..,"sharePct":..,
              "slow":..,"slowAvgMs":..}, ...]}
```

### `/api/spawnbot?id=<charId>[,<charId>...]`
Bring offline bot character(s) back online as managed self-owned autopilot bots (reuses
`BotManager.spawnManagedBot`, the `BotScheduler` path). Respawns at the character's saved map/position
with its real level/job/skills/stats — it does **not** synthesize stats. For a controlled repro, respawn a
character that already has the build you want, then `/api/command moveto x y` to drop it on an exact spot
(e.g. a lv10 mage next to a snail). State-changing GET (LAN debug only). `spawned:false` = already online
or load failed.
```
{"results":[{"id":767,"spawned":true},{"id":814,"spawned":false,"note":"already online or load failed"}]}
```

### `/api/navprobe?id=<botCharId>&x=<>&y=<>[&skills=1][&mode=normal|exhaustive]`
Pathfinding probe: runs the bot's own nav search (`BotNavigationManager.runSearch` on the live graph)
from its current position to an arbitrary point on its current map. The "why can't the bot get there"
companion to `/api/bot/pathlog` — answers reachability for a hypothetical target (e.g. a portal's
approach point) without driving the bot there. `targetGroundY=-1` / `targetOnRope` flag the target
surface. `mode=normal` mirrors the live executor's bounded `"committed"` search, including best-effort
redirects for unreachable targets; `mode=exhaustive` runs a strict unbounded proof search.
`&skills=1` runs the **skill-enabled** planner — teleport / flash-jump edges the bot is eligible for by
skill possession (no MP/meso gate, no cost-saved threshold) — so the path can include `TELEPORT`/
`FLASH_JUMP`; default is walk-only. Probe a teleport mage / flash-jump hermit to confirm the planner
routes through skill edges. The response echoes `skills`.
```
{"bot","map","from":[x,y],"to":[x,y],
 "fromRegion","toRegion","targetGroundY","targetOnRope","skills":bool,
 "mode":"normal|exhaustive","canReach":bool,"reachable":bool,"reached":bool,
 "bestEffort":bool,"capped":bool,"finalRegion":n,"cost":n,"expanded":n,"hops":n,
 "path":[{"type":"WALK|CLIMB|JUMP|DROP|TELEPORT|FLASH_JUMP","fromR","toR","cost","lsx","from":[x,y],"to":[x,y]}, ...]}
```
`reachable` is an alias of `reached`: true only when the path actually lands in `toRegion`.
**redirecting** `"committed"` search — for an UNREACHABLE target it returns a best-effort partial path that
stops at the closest useful region instead of the exact target; `bestEffort:true` marks this case.
This is expected for some unreachable NPC/portal pixels where walking near enough is still useful to the
runtime `stuckNear`/interaction fallback.

### `/api/pathfind?id=<mapId>&from=<regionId>&to=<regionId>[&sp=<>&jmp=<>&snow=0|1][&mode=normal|exhaustive][&tp=1][&fj=1]`
Region-to-region pathfind for the `/mapgraph` UI: click **Pathfind**, click a source region, click a target
region — it draws the route and verdicts it. Pathfinds on the SAME cached movement-profile graph the page
renders (`sp`/`jmp`/`snow`; default base sp100/jmp100). Runs the **same `BotNavigationManager.runSearch` the
live bot uses** (SSOT — no parallel pathfinder), in one of two modes:
- `mode=normal` (default) — the live executor's `"committed"` **redirecting best-effort** search with the
  bot's bounded edge-check budget. On an unreachable/too-far target it walks AS CLOSE AS POSSIBLE; the
  `explored` array is the frontier it checked (drawn faint teal, clickable) so you can see where it gave
  up. It is de-duplicated to the **distinct** edges checked (A* re-pops regions, so the raw sink repeats the
  same edge many times); genuine parallel launch-x variants stay separate — those are the redundant edges.
- `mode=exhaustive` — **strict, UNBOUNDED** search: exhausts the graph so an empty path is a definitive "no
  route". `canReach` (a full directed reachability BFS) is the exhaustive proof of (un)reachability.

The tool has no live bot, so skill edges are **off by default (walk-only)**. `tp=1` enables teleport (mage)
edges and `fj=1` enables flash-jump (thief) edges — both `canReach` and the search honour the mask (via
`runSearch`'s `forcedSkillMask`, so no synthetic bot is needed). The response echoes `teleport`/`flashJump`.

Reachability uses the same verdict fields as `navprobe`: `reached:true` = genuine route;
`bestEffort:true` = produced a partial that stops at `redirect`
(`canReach:true` → A* capped; `canReach:false` → real graph gap, the "stuck in a movement loop" target);
`canReach:false, path:[]` (exhaustive) = proven unreachable.
```
{"map","from","to","profile":{sp,jmp,snow},"mode":"normal|exhaustive","teleport":bool,"flashJump":bool,
 "canReach":bool,"reachable":bool,"reached":bool,"bestEffort":bool,"capped":bool,
 "finalRegion":n,"cost":n,"expanded":n,"elapsedMs":n,"hops":n,"redirect":<regionId|-1>,
 "path":[{"type","fromR","toR","cost","lsx","from":[x,y],"to":[x,y]}, ...],
 "explored":[ ...same edge shape; only populated for a best-effort result... ]}
```

## Settings API

### `/api/fleetops?owner=<ownerCharId>`
Plain text: the lines `navi fleet` whispers that owner (training bots, fleet EXP/h over the last hour, stuck,
looping, no EXP, slowest, capped), for checking fleet ops without logging in. Read only. Backed by `BotFleetOps`,
whose once a minute sampler keeps 90 minutes of history per bot in `cache/fleet-ops/samples.tsv` (the
server-cache volume), so a restart picks up where it left off; only a first boot waits 10 minutes for EXP/h.

### `/api/fleetops/incidents`
JSON array of stalls a return scroll can't fix (`errand_loop`, `no_exp_on_errand`, `refreezing`,
`no_exp_after_rescue`), each with the bot's map, errand, stall minutes, auto rescue and responder counts,
status line, bag fill per tab and its top USE stacks with the sell rule that classifies each. Read only.
Polled by [tools/responder](../../tools/responder/README.md).

### `/api/learned/readiness`
How close the grind target decision log (`logs/bot-decisions/`, docs/bot/learned-grind-target.md) is to
worth training on. Counts every logged pick since the first log, earlier days included (scanned once,
in the background, the first time it's needed). Read only. Shown on the world map page.
`model` is the learned scorer the model arm uses (stage 3, `BotGrindTargetModel`, loaded from
`models/grind-target-model.json` and reloaded within a minute of the file changing), or null when every bot
uses the rule; `share` is `GRIND_TARGET_MODEL_SHARE`.
```
{"picks":38,"explored":1,"maps":1,"need":{"picks":20000,"maps":10,"explored":300},"ready":false,
 "model":{"kind":"trees","version":"trees-20261019-104500-1a2b3c4d","share":0.5}}
```

### `/api/fleetops/act` (POST)
`{"bot":<char id>,"action":"rescue|hold_errands|sell_trash|park","arg":<minutes>,"reason":"..."}` runs one
responder action on the bot's next tick and whispers its owner. At most 3 per bot in 2 hours. Not reachable
through the LAN share, which passes GET only.

### `/api/party` (POST)
`{"ids":[<leader char id>,<member char id>,...]}` puts online self owned bots (roster or managed, never a player's
companion) in one game party led by the first id (`BotManager.partyUp`) and sends the cohort out together
(`BotAutopilotManager.startParty`, the crew path: one map worth it for all, party EXP and party buffs). Plain
text `partied <names>` or why not (400). Not reachable through the LAN share, which passes GET only.

### `/api/settings` (GET + POST)
Live admin/tuning surface behind `/admin`. Same SSOT as the GM commands: `BotConfigReflect` (the
`!botcfg` reflection), `BotScheduler` (`@botpop`), `BotLlmConfig` (`!botllm`), `BotAdminOps` (`@botpop wipe`).

**GET** — snapshot of every tunable group:
```
{"manager":[{"name","value","type"}, ...],   // BotManager.cfg public fields (POPULATION_MULTIPLIER, break/loot/autopilot/party knobs)
 "combat":[{"name","value","type"}, ...],     // BotCombatManager.cfg public fields (the !botcfg set)
 "pop":{"enabled":bool,"multiplier":num,"status":[lines...]},
 "llm":{"enabled":bool,"debug":bool}}
```

**POST** — mutate one knob; dispatch on `cmd`:
- `{"cmd":"set","group":"manager|combat","field","value"}` → set a config field (case-insensitive). Returns `{"ok","msg"}` (`msg` starts with `OK` on success, mirrors `!botcfg`).
- `{"cmd":"pop","mult"?,"enabled"?,"sweep"?}` → set multiplier / toggle scheduler / force a sweep. Returns `{"ok","status":[lines...]}`.
- `{"cmd":"llm","enabled"?,"debug"?}` → toggle LLM chat (`debug:true` implies on). Returns `{"ok","enabled","debug"}`.
- `{"cmd":"perflog","seconds":1-300,"html"?}` → enable `BotPerformanceMonitor`, capture one clean window for `seconds`, export `logs/bot-perf/bot-perf-<ts>.csv`; `html:true` also runs `tools/botperf_report.py` to write the `.html` report next to it. Blocks for `seconds`. Returns `{"ok","msg":"CSV: ...|HTML: ..."}`.
- `{"cmd":"disconnectAll","confirm":"DISCONNECT"}` → disconnect every online bot (scheduler may respawn them). Returns `{"ok","disconnected":n}`.
- `{"cmd":"wipe","confirm":"WIPE"}` → **permanently delete** every managed bot (shared with `@botpop wipe`; real/shared accounts skipped). Returns `{"ok","wiped","skipped","lines":[...]}`.

Destructive verbs require the exact `confirm` token (the page prompts for it) — no real auth, LAN-only.

### `/api/inventory?id=<botCharId>`
An online bot's four bag tabs, read live from the game: `{id, name, meso, map, tabs:[{type, limit, items:[{s, id, n,
q, v?, r?}]}]}` with `type` in `EQUIP | USE | SETUP | ETC`, `s` the slot, `n` the item name, `q` the quantity, and `v`
what the sell pipeline does with it (`sell` on the next shop trip, `shelf` only when the tab is cramped, `keep`)
with `r` the reason label the `inv debug` dump prints (`BotInventoryManager.bagVerdicts`). Set-up items carry no
`v`. `404 {"error"}` when the bot isn't online; `503` when the bag changed mid read (poll again). The world map
page opens it from the **items** button on a bot row (roster or right panel) and polls every 5 s; icons come
from maplestory.io in the viewer's browser. The **graph** button beside it opens `/mapgraph` for the bot's map.

### `/api/craftplan?id=<botCharId>`
The craft an online bot would start now, computed live by the same `BotCraftManager.choose` its errand uses
(bag plus its free bins from the roster snapshot): `{id, name, level, meso, active, bins, plan, rejected, near}`.
`active` is true while a craft errand runs; `bins` counts the free bins with ETC stock. `plan` is null or
`{goal, n, scrollBase, gain, fees, inputValue, steps:[{npc, id, n, times}], pickups:[{mule, items:[{id, q}]}]}` (steps in run
order, the goal last; pickups empty when the bag covers it; `scrollBase` true when the goal beats the worn piece only
once scrolled). `rejected` counts the gear recipes that lost, by
reason; `near` lists up to 10 that would be upgrades (`gain` > 0), biggest first, with `why` they lost. `404` when
the bot isn't online; `503` when the bag changed mid read.

### `/api/craftdemand`
Fleet material demand for crafting (`BotCraftManager.wants` per online bot, mules on duty skipped): the raw
materials (ores, monster drops, base gear) the bots lack beyond their bags and bins for upgrades that pass every
other craft gate, one recipe per output. `{bots, materials:[{id, n, bots, upgrades, qty, gain, alone, aloneGain,
for}]}`, top 25. `upgrades` and `gain` count every upgrade the item is missing for; `alone` and `aloneGain` only
those it is the last missing item of, which is what farming it would unlock by itself; `qty` sums what each
upgrade needs; `for` names up to three of the biggest. Ranked by `aloneGain`, then `gain`. Computed on request,
a full plan search per bot.

### `/api/scrollmatch`
Equip scrolls fleet members hold but can't use, and who in the same owner's fleet could, from the roster snapshot
(offline members included; up to 30 minutes old): `{snapshotAgeS, strandedWithTaker, strandedNoTaker, matches:[{holder,
holderId, id, n, q, takers:[{id, n, level}]}]}`, biggest stacks first. A scroll counts when it is routable
(`BotOfferManager.isOfferableScroll`: no clean slate, chaos, modifier or white scroll) and useless to its holder by
`BotOfferManager.scrollUsefulTo` (a stat the job values, applying to a worn non cash piece); `takers` are the members
it is useful to by the same rule. The two totals count scrolls with and without a taker. `503` while the snapshot loads.

### `/api/gearmeta?from=10&to=120&step=5&watch=<id,id>`
Which gear the bots' gear score ranks best (`BotGearMetaReport`, `BotScrollManager.potentialValue` per job with
catalog scroll headroom only), per class (one job each: 110, 210, 310, 410, 510), armor and accessory kind, and level:
`{from, to, step, classes:[{class, job, kinds:[{kind, reigns:[{id, n, from, to, levels, score, src}]}]}], watch:[{id, n,
reqLevel, src, bestLevels:{class: levels}}]}`. A reign is a run of sampled levels one item ranks best; `src` is `drop`,
`shop` or `craft` (unobtainable items are left out). `watch` defaults to the meta pieces (Zakum Helmet, Pink Adventurer
Cape, Red and Purple Snowshoes, Work Gloves). Clean catalog stats, stat requirements ignored. Scans every equip.

### `/api/gearplan?id=<botCharId>`
The gear that pays back fastest in leveling for an online bot, given how strong it is now (`BotGearPlanReport`):
every obtainable piece it can wear today (level, job, stats, preferred weapon type) that beats the best it owns for
the slot by the grind planner's gain (`BotGrindAdvisor.catalogAcquireGain`, an overall against top plus pants),
priced by its cheapest route (`BotScrollManager.cheapestAcquisition`: a shop's price, this bot's own farming effort
on the best dropper, or an NPC craft) in hours at the effort anchor (1,000 meso a second). `expPerHour` is what it
adds on the bot's grind map (`expPerHourWithDpsGain`: kill time shrinks by the damage gain down to one attack cycle,
capped by respawn) and `payback` the hours of grinding with it that repay the detour (`paybackHours`; null when it
levels no faster), `wear` the hours until a better obtainable piece can be worn (or the fleet's outgrown line when
none is listed), and `worth` whether payback beats
wear (`BotGearPayback.judge`, the rule the gear trip and the craft errand use). `{id, name, level, job, grind:{id, n,
mob, expPerHour, killSeconds}, best:[pick], slots:[{slot, worn:{id, n, score}, picks:[pick]}]}` with `pick` = `{id, n,
req, gain, via, hours, expPerHour, payback, wear, worth}`; ranked by
payback, `best` the top 10, each slot its top 3. A shop route here is its price only; the gear trip adds the round trip
to the shop (the Bathrobe is sold only in Zipangu). `grind` is null in a town with no grind map. `404` when the bot isn't
online.

### `/api/bossplan?ids=<botCharId>,<botCharId>`
Which boss runs pay back for a party of online bots, and what each member needs first to survive one
(`BotBossPlanReport`). Bosses come from Map.wz life (boss flagged, respawning, `respawnHours` from `mobTime`) and the
area boss event scripts (`scripts/event/*.js` naming one map and one monster; `respawnHours` is the recheck period),
on fields some member can travel to. Per member: `dps` on the boss (the grind planner's kill model), `maxHit` (its
hardest landed touch, the damage bots take), `hp` (Magic Guard counted when up), `trip` (round trip hours), `touchLoss`
(the share of its HP one touch attempt takes on average, the grind planner's danger primitive) and `need`:
`ready`, `can't reach`, `can't hurt it`, or the HP to live through two hardest touches. Ready members fight: `killMinutes`
is the boss HP over their summed damage, `runHours` the longest trip plus the kill, `partyExp` the boss experience split
by damage, `forgoneExp` what the fighters would have ground in that time, and `expWorth` whether the run beats grinding
on experience alone. `drops` (top 3) are gear drops a fighter can wear now, each judged for the fighter who gains most
by `BotGearPayback.judge` over `runs` expected kills (net of the experience they earn), with `days` the calendar time
those runs span at the respawn and `wearLeft` the wear time once the waiting is spent leveling. `worth` is `expWorth`
or the best drop's payback beating its `wearLeft`. `{party:[{id, n, level, job, hp, grind:{n, mob, expPerHour}}],
offline:[id], bosses:[{mob, n, level, hp, map, mapName, respawnHours, source, killMinutes, runHours, partyExp,
forgoneExp, expWorth, worth, fighters:[{id, n, dps, maxHit, hp, trip, touchLoss, need}], drops:[{id, n, taker,
chance, runs, days, payback, wearLeft, worth}]}]}`, worth first, then by experience return; a boss on several fields
shows its best site. Boss skills and magic attacks are not modeled (bots take touch damage only), potions and how
often a boss lands touches over a long fight are not priced, and there is no channel hopping for more spawns. `404` when no listed bot is online.

## Write API

### `/api/chat` (GET, POST)
Dashboard chat with one managed bot (`BotDashboardChat`). `GET /api/chat?id=botCharId` returns the
conversation `{"lines":[{"t","bot","m"}, ...]}` (epoch ms, `bot` true for the bot's lines, message), oldest
first, last 40 lines, in memory only. `POST /api/chat?id=botCharId` with the message as the plain text body
(1 to 200 characters) says it to the bot as its owner, like an owner whisper: chat commands first, then the
LLM reply when no command matches. Replies go to reply channel `DASHBOARD`, so they appear here and nothing
is said in game; the channel stays until the owner next talks to the bot in game. Returns the same JSON as
GET, or `409 {"error"}` when the bot isn't online or isn't managed (a bot whose player owner is online is
talked to in game). The world map page opens it from the **chat** button on a bot's row in the right panel.

### `/api/command` (POST)
RTS control. Body `{"cmd","ids":[botCharId,...],"maps":[mapId,...]?,"target":charId?,"x":int?,"y":int?}`.
`cmd` ∈ `idle | fidget | move | moveattack | moveto | follow | resume | dance | jump | cheer`. Applies to each
commandable bot id; `move`/`moveattack` resolve a per-bot destination from `maps[]` (clicked node:
hub-first, else nearest); `moveto` walks each bot to an exact `x`,`y` on its **current** map via the full
nav pipeline (A*/jumps/climbs) and holds there — the live physics-debug + RTS "go exactly here" surface
(exposed in the RTS toolbar as the x/y inputs + **Go xy**); `follow` needs `target`. Returns
`{"ok","applied","skipped":[ids...]}`. Commands persist ~30 min, then revert to autopilot (`resume` ends now).
