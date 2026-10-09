---
name: kb_bot_findbelow_nearest_and_void_fall
description: Two movement bugs found 2026-10-09 from stuck roster bots. (1) the ground probe returned the first foothold in Foothold.compareTo order instead of the nearest one, so steep slopes broke falls, jumps and graph edges; (2) a self-owned bot falling through a floorless column was never recovered.
metadata:
  node_type: memory
  type: reference
---

**1. Nearest foothold below (GRAPH_VERSION 68).** `BotPhysicsEngine.findBelowIndexed` mirrored `FootholdTree.findBelow`: sort by `Foothold.compareTo`, return the first foothold at or below the point. `compareTo` orders by y extent and returns 0 whenever two footholds' y ranges overlap, so a long steep slope ties with every flat platform inside its y range and the sort keeps insertion order. The probe then returned a LOWER surface than the one the bot meets.
- Evidence: SipsBuddy3 stuck 13 min on map 682000100 (Haunted House foyer) at (17,637), `nav=no-path` to portal cm01 (17,-313). The stair rail fh30 (-958,276 to -718,568) lost to the steps under it, so falls passed through the rail and jumps from the rail passed through the 325 floor fh72. The graph had no JUMP r31->r35 and the bottom cluster (10 of 42 regions) could reach the top only by FLASH_JUMP or TELEPORT. A cleric has neither.
- Fix: take the minimum floor y at or below the point (ties keep sorted order). After the fix all 42 regions connect by JUMP and DROP, and `BotMovementSimulationCli 682000100 --bot b:17,630 --move b:17,-313` reaches the portal in about 36 s.
- Test: `BotFootholdBelowTest` (synthetic slope plus flat floor in both insertion orders). Upstream `FootholdTree.findBelow` still has the bug; left untouched (non-bot code).

**2. Void fall recovery.** On map 221023300 (Eos Tower 75th Floor) the side wall sits at fh43's tip, x=562, past the bottom floor's end (fh91, x 549). A missed rope grab hit the wall, zeroed airVelX, and fell at x=562 forever (y past 370,000). Nothing recovered it:
- `tickFrozenAirborneWatchdog` needs an unchanged position, and a falling bot moves every tick;
- the 600px OOB recovery in `recoverTeleportDistance` measures distance to the owner, which is the bot itself for a self-owned bot (see [[kb_bot_self_owned_owner_assumptions]]), and autopilot travel ticks skip it anyway.
- Fix: the watchdog also recovers an airborne bot once `BotPhysicsEngine.isBelowMapFloor` (VR bottom + 600, the same bound the fall sims use) is true, teleporting to the active goal's ground like the frozen case. Test: `BotManagerTest.shouldRecoverSelfOwnedBotFallingBelowTheMapFloor`.

**Diagnostics used:** `/api/bot/pathlog?id=` (call twice), `/api/mapgraph?id=` reachability with JUMP and DROP only, and `BotMovementSimulationCli` run in the maven docker image with `target/classes:target/test-classes` plus the dependency classpath.

**Known flaky test, not related:** `BotMovementSimulationLabTest` flat ground tests fail when run after `BotPhysicsEngineTest` (map id 910000102 reused with different footholds; per map id caches). Fails on the unchanged code too; passes alone.
