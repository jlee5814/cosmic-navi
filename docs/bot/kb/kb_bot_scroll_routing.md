---
name: kb_bot_scroll_routing
description: "Scroll routing through the storage bins: who can use a scroll (fleet wide, from the roster snapshot), the holder handoff into the gear kind's bin, the taker's pickup errand, and the first fleet read"
---

Built 2026-10-16 (`BotScrollRouting`, `BotOfferManager.fleetScrollMatches`); flow in `docs/bot/etc-storage-mules.md`.

**The rule**
- `BotOfferManager.scrollUsefulTo`: the scroll grants a stat the job values and applies to a worn non cash piece.
  One core on ids (job, worn ids) serves the live `Character` and the roster snapshot (`Member.wornIds`,
  `Member.scrollsHeld`, loaded with the other inventories). `ScrollRules` is the item data seam for tests.
- Beginners are never takers: every stat counts for them and they scroll nothing (the fleet demand rule).

**Moving them**
- Holder: `handoffScrolls` adds scrolls with a taker to the visit's handoff; `planBins` routes a scroll by
  `binRole` (the gear kind's bin via `kindRole`, class from its takers), everything else by `roleFor`.
- Taker: the scroll pickup errand, gated on `selfScrollEnabled`. `wanted` (pure) caps by open slots per gear kind,
  best `ev` (effective success times offense gain) first. Mules give from any tab now (`give`).
- The pickup is shared with the craft errand: `BotEntry.pickups`, `pickupNeed`, `pickupSinceMs`, and
  `BotStorageMuleManager.startPickup`, `tickPickup` (TRAVELING, TRAVEL_YIELDED, COLLECTING, GOT_ALL, SHORT),
  `releasePickups`.

**First fleet read** (`/api/scrollmatch`, 2026-10-16): 1,619 scrolls sat with members who can't use them; 365
had a taker. The rest have none: One-Handed Blunt Weapon attack scrolls (about 150; no member wields one),
Two-Handed Sword accuracy, and pet equip speed scrolls (no job values speed). Owner call: those stay put.

**Fleet held scrolls in the gear score** (2026-10-16, owner decision)
- `potentialValue` counts slots the fleet's own scrolls can fill at full worth (see [[kb_bot_grind_gear_valuation]]),
  so shop, craft and grind choices lean toward gear the fleet already has scrolls for.
- The usefulness rule counts carried gear as well as worn (`BotOfferManager.gearIds`: snapshot `bagCopies`, live
  equip bag): a blunt weapon a Page bought or crafted makes it a taker, so the holder routes those scrolls into
  Warrior weapons. The taker's room (`roomByKind`) counts a bag piece only when it beats the worn one by the gear
  score, so a junk spare pulls nothing.
