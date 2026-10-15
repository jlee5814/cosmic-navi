# Storage mules for ETC materials (design draft, 2026-10-14)

Status: draft for review. The ETC value shelf (`BotInventoryManager.etcShelf`, `collectCrampedEtcSales`) ships
first and stays as the backstop; this document covers the mule visits that come after it.

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

## Visit flow

1. Trigger: the ETC tab is cramped and the shelf holds items a bin takes, the bot is in or near a town, and no
   visit ran in the last hour (jittered). Checked where the shop trigger runs (`BotShopManager.shouldAutoSellTrash`).
2. Pick the bins: group the shelf items by bin; skip a bin that is full (fewer free slots than the stacks it
   would take after merging). What no bin takes goes to the next shop visit as today.
3. Summon: each chosen mule logs in for one session (`BotManager.spawnManagedBot`, the same call as
   `/api/spawnbot`; it does not change `rosterActive`).
4. Meet: both walk to the meeting place (see the open questions), with human delays.
5. Trade: the existing peer trade sequence (`BotInventoryManager` trade batches, 9 items per window,
   `TRADE_WINDOW_ITEM_LIMIT`), the mule accepting as a peer partner. `isPeerTradePartner` accepts the same
   owner or crew today; mules are self owned and not crewmates, so it gains a third arm: the same registered
   owner (`BotOwnershipService`, the roster stable).
6. Leave: the mule logs out once its trades finish or after a timeout; the bot resumes its plan.

About 10 trades move 90 slots, a few minutes in all, roughly a shop trip. Expected once every few hours per
bot.

## Rules

- Never trade away a ferry ticket, a skill consumed item, or anything the shelf would keep (`onEtcShelf` is
  the SSOT for what may leave).
- A mule only receives. It never sells, crafts or grinds; its bag is the record of what the fleet holds.
- If a mule is online already (a player logged it in), it is not summoned again; if a player owns the
  session, the visit skips that bin.
- The shelf still sells when no bin can take an item or the visit fails.
- The dashboard item window shows each mule's bags, so a full bin is visible.

## Open questions

1. Meeting place: the Free Market (players trade there; every town has a portal, but the bot graph's route
   into 910000000 is unverified), the mule walking to the bot's town, or the bot walking to the mule's map.
2. Working set: should a bot keep one stack of each material for its own future crafting or quests, or hand
   everything over?
3. A full bin: sell its lowest value stack, or notify the owner?

## Order of work

1. ETC value shelf (shipped first, the backstop).
2. Peer partner arm for the same registered owner; mule summon and logout.
3. Visit trigger, bin routing, meeting, trade batches.
4. Live check on SipsBuddy2, then the fleet.
