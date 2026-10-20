---
name: kb_bot_pit_traps
description: "Two pit traps that froze mains on Oct 19: travel read a bot on a rope as standing in the pit below (Dragon Forest I into Hidden Dragon Tomb II, #190), and physics let a wall win a tie with the floor so a pushed bot fell through Helios 99F's elevator floor (#191). How each was caught"
---

**Dragon Forest I loop (SipsBuddy1, #190).** 240030100 hangs its climb rope (x 1486, y -956 to -297) over a pit
whose floor (y -178) holds in03, a collision portal into the dead end Hidden Dragon Tomb II (240030104). The tomb's
only exit lands at st01 (1586, -478), next to the rope. `BotTravelManager.tickTravel` read the bot's region with
the raw `findRegionId`, which projects a bot on a rope or mid jump onto the floor below it: the pit. From the pit
east00 is unreachable, so the commit revalidation dropped it, the partition router replanned through in03, and the
bot climbed down into the pit. It looped for hours. Fix: travel takes the region from
`BotNavigationManager.resolveCurrentRegionId` (the nav SSOT: rope region while climbing, none while airborne over a
gap). Rule: anything that asks which platform a bot stands on goes through that SSOT, never the raw lookup.

**Helios 99F pit (SipsBuddy3, #191).** 222020200's elevator floor (fh 199, y 2040, forbidFallDown) runs wall to
wall between x -265 and 265 over a sealed pit at y 2342, below the view rect. A bot on the floor's end pixel pushed
into the wall with no lift met the wall and the floor in the same column; `resolveAirCollision` let the wall win the
tie, the bot hung at floor height, and the straight fall after it probes from one pixel below the floor, so it
dropped through. The graph had two flash jump edges into the pit. Fix: on an exact tie a landing wins when its floor
runs under the bot's side of the wall (graph version 79). A floor reaching the wall only from the far side still
loses, which `shouldKeepAirborneBotOnNearSideOfCollidableWall` pins.

**How they were caught.** A watcher polled `/api/botdebug` every 15 s and dumped `/api/bot/pathlog` on a stall;
for the loop, a capture timed to the tomb exit (start recording at y < -1680, dump 4.5 s later) showed the goal
switch at the same tick both times. For Helios, which never reproduced live, a knockback sweep on the real map
(`BotWallFloorTieTest`) and `/api/mapgraph?id=222020200` (edges whose end y is in the pit region) found it. A
sealed region with inbound edges and no outbound ones is the signature of a pit trap; sweep for it.

**Unparking.** A responder park sets rosterActive=false; `/api/spawnbot?id=N&unpark=1` (#192) restores it.
