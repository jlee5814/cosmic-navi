# Storage mules for ETC materials (2026-10-14)

Status: built (`BotStorageMuleManager`). The ETC value shelf (`BotInventoryManager.etcShelf`,
`collectCrampedEtcSales`) stays as the backstop; this document covers the mule visits on top of it.

## Goal

A bot's ETC tab fills with maker materials it can't craft with. The shelf sells the cheapest stacks when the
tab is cramped, which frees slots but throws away the materials' crafting value. The roster already names 40 storage characters (SipsBuddy61 to SipsBuddy100) as
category bins. A mule visit moves materials to the bin that holds them, the way a player hands loot to a
storage character, and keeps them for whoever crafts.

## Capacity (2026-10-14)

- 40 mules with 96 ETC slots each: 3,840 slots, 663 used.
- The fleet's maker materials fill about 2,570 slots in partial stacks (ores, jewel ores, crystal ores,
  powders, stimulators and manuals). Merged at 100 per stack on the mules they need about 750.
- Mules are a buffer, not a sink. Drops keep coming; the bins fill unless someone crafts or sells. The shelf
  decides what goes when a bin is full.

## Bins

From the roster (`docs/bot/rosters/sipsbuddy.csv`, tier `storage`). Item ranges follow
`BotInventoryManager.isMakerMaterial`.

| Mule | Role | Takes |
|---|---|---|
| SipsBuddy86 | Power crystals | stat crystal ores 4004000 to 4004004, crystals 4005000 to 4005004 |
| SipsBuddy87 | Dark crystals | Dark Crystal Ore 4004004, Dark Crystal 4005004 (split from 86 by item) |
| SipsBuddy88 | Diamond, sapphire, aquamarine | matching jewel ores 4020xxx and jewels 4021xxx |
| SipsBuddy89 | Emerald, topaz, opal | matching jewel ores and jewels |
| SipsBuddy90 | Garnet, amethyst, black crystal | matching jewel ores and jewels |
| SipsBuddy91 | Iron and bronze | Bronze Ore 4010000, Bronze Plate 4011000 (no iron ore exists in v83) |
| SipsBuddy92 | Mithril, adamantium, steel | ores 4010001 to 4010003 and their plates |
| SipsBuddy93 | Processed materials | magic powders 4007xxx, stimulators and manuals 413xxxx, monster crystals 426xxxx |
| SipsBuddy94 | Monster ETC drops | none by default: mob drops still sell |
| SipsBuddy95 | Quest materials | quest items the bot holds for a started quest it can't finish yet |

The Processed materials bin takes stimulators and manuals because no role names them; at about 100 slots
merged they may need a second bin. Silver, Orihalcon and Gold ores (4010004 to 4010006) have no named bin
either; the Iron and bronze bin can take them until a role does.

## Maple gear (owner rule, 2026-10-15)

Every piece of Maple gear a bot holds (any equip whose name says Maple: weapons, shields, hats, capes,
earrings) is never sold, by any sell path, and goes to a bin on the next visit
(`BotInventoryManager.isMapleItem`, `BotStorageMuleManager.mapleBinRole`). Maple Throwing-Stars get the same
rule (owner, 2026-10-15): never sold, always looted, handed to the Thief weapons bin unless the holder throws
them itself. Other Maple items (leaves, coins, food) follow the normal sell rules.

- Weapons to the weapon bin of the class that uses them (lowest reqJob bit: Warrior, Mage, Bowman, Thief,
  Pirate); hats, tops, overalls, pants and shields to that class's armor bin.
- Gloves, shoes and capes to their own bins; earrings, face and eye accessories, rings, pendants, belts and
  medals to Accessories; gear any job can wear to Rare and high-stat equipment.
- Kept back: a piece the bot would wear next (own upgrade) or has promised a teammate, quest items, and
  anything that would vanish on the floor.

Maple gear in the bag starts a visit on its own (still once per 45 to 90 minute cooldown); the visit hands
over the shelf materials too. A farmer whose needed mule was busy with another farmer's visit waits for it
(up to 4 minutes after its last bin) and calls it in when it frees.

## Visit flow

The handoff is a drop in the Free Market, the way players hand loot to a storage character: the mule stands
there first, the farmer drops a batch beside it, the mule picks it up.

1. Trigger (`maybeStartVisit`, a detour errand after the gachapon in `BotAutopilotManager.DETOUR_ERRANDS`): an
   autopilot farmer, not following, no shop trip or errand pending, ETC tab cramped, off its cooldown (45 to
   90 minutes after the last visit), and its shelf holds stacks a free mule takes. Checked every 30 s.
2. Bins (`planBins`): the shelf grouped by `binRole`, each role mapped to the mule with that `rosterRole` under
   the same registered owner (`bot_owners`). The directory loads off the tick thread and refreshes every 30
   minutes. A mule someone else has online, one on another farmer's visit, or one found full in the last 6
   hours is skipped.
3. Summon: each chosen mule goes on duty and logs in (`spawnManagedBot` on a daemon thread; `rosterActive`
   stays as it is). On duty it walks to the Free Market (910000000) and waits by the exit portal.
4. Route: 22 towns enter the market through scripted portals (`BotWorldGraph.SCRIPTED_ENTRANCES`, scripts
   marketNN save the town as FREE_MARKET). The only way out, out00, returns to that saved town, so the exit edge
   exists only for a bot standing in the market (`RouteOptions.fmReturn`, from `peekSavedLocation`), the same
   pattern as the Mushroom Shrine.
5. Handoff (`tickVisit`): the farmer walks within 60 px of the mule and drops up to 8 stacks (never more than the
   mule's free ETC slots), whole stacks, through `InventoryManipulator.drop`. The farmer keeps its own loot off
   meanwhile. The mule walks onto the newest drop and passive loot takes it; `BotLootEligibility` lets the mule on
   duty skip the 15 s wait other bots have for a bot's drop (`isPartnerDrop`). The next batch waits for the
   floor to clear (30 s at most), with a jittered pause.
6. Leave: a bin is done when its stacks are gone, the mule is full, or a batch sat 30 s. The mule logs out after
   a short linger; the farmer leaves through out00 and resumes its plan.

Dropped items vanish after 3 minutes and the map holds 200, so the mule arrives before anything drops, and
a batch is small.

## Rules

- Only shelf items leave (`onEtcShelf` is the SSOT): no ferry tickets, nothing a skill consumes, nothing the
  shelf keeps.
- A mule only receives. It never sells, crafts or grinds; its bag is the record of what the fleet holds.
- While a visit is under way or due (`holdsShelf`), an automatic shop trip sells only true trash and leaves the
  shelf alone. A visit that ends, well or not, starts the cooldown, and whatever stayed behind sells on the next
  trip. An owner's explicit sell order still sells the shelf.
- The dashboard item window shows each mule's bags, so a full bin is visible.

## Decisions (2026-10-14)

1. Meeting place: the Free Market, with a drop handoff instead of trade windows.
2. Working set: every shelf item with a bin goes; the bot keeps nothing back.
3. A full bin, or a visit that fails: the shelf sells, as before.

## Order of work

1. ETC value shelf (shipped first, the backstop).
2. Free Market routing, mule summon and logout, visit trigger, bin routing, drop handoff (this change).
3. Live check on SipsBuddy2, then the fleet.
4. Later: something that empties a bin (a crafter, or the mule selling its lowest value stack).
