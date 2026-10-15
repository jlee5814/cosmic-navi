---
name: kb_bot_ferry_ticket_sell_loop
description: Why SipsBuddy2 looped between the Ludibrium ticket seller and the shop until the navi responder parked it (the sell pass took its ferry ticket), the keep that fixes it, and the bag full case left open. Read before changing what the ETC sell pass keeps or how ferry tickets are bought.
type: project
---
# Ferry ticket sell loop (Ludibrium, 2026-10-13)

## Finding

SipsBuddy2 (Bishop, lv 70) spent most of Oct 13 in Ludibrium: shop trips every 10 to 20 minutes with
thousands of potions, two auto rescues from the Ludibrium Ticketing Place, and at 15:51 EDT the navi
responder parked it (`rosterActive=false`) for buying and selling its Orbis ticket.

Its ETC bag held 93 of 96 slots, almost all maker materials, which `isMakerMaterial` keeps (see
[[kb_bot_maker_economy]]). Buying the Orbis ticket (4031045, 6,000 meso) left 2 free slots, so the bag was
cramped (`AUTO_SELL_FREE_SLOT_THRESHOLD`), and the ticket was the only ETC item
`collectSellTrashEtcItems` would sell. `BotAutopilotManager`'s pre travel check (`bagFull`, which is
`shouldAutoSellTrash`) then sent the bot to the shop (220000002) to sell it, and the next travel plan walked
back to the seller to buy another.

## Fix

`BotFerryManager.isTicket(itemId)`: any ticket a ferry route takes as fare, built from the route table
(SSOT). `collectSellTrashEtcItems` keeps it beside the skill consumed ETC items; the inventory dump labels
it `ferry-ticket`. With nothing else to sell, a cramped ETC bag no longer starts a sell trip.

Test: `BotInventoryManagerTest.aFerryTicketIsTravelFareNotTrash` (fails on main).

## Open

- A full ETC bag (96 of 96) can't take a ticket: `ticketShop.buy` mirrors the seller script's space check.
  Since the ETC value shelf (2026-10-14), a cramped tab sells the cheapest maker material stacks a bot can't
  craft with on its next shop visit, down to 8 free slots. Mule visits that keep them are designed in
  `docs/bot/etc-storage-mules.md`.
- The navi responder's hold_errands calls on Oct 13 treated the loop as noise; each one bought 30 to 60
  minutes before the loop resumed.
