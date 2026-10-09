# Fleet strategist report (claude-sonnet-5-5)
Snapshot 2026-10-09 19:26: 33 bots. Read only; nothing was sent to the game.
Proposed changes: 13 (12 pass the checks); keep: 20; plans failing a check: 1. 74 s.

## Findings (new problem classes, for the owner to approve as code)

- **Bots flagged weapon_gap hold mesos for an affordable, wearable weapon but have not bought it.** (SipsBuddy29, SipsBuddy30, SipsBuddy34)
  - Evidence: Fish Spear lv20 is 20 to 22 levels below the bots. Zeco costs 225,000 mesos; they hold 4.77M, 8.25M and 3.21M. Options show 40.7 to 43.0 EXP/s with Zeco versus 24.4 now.
  - Proposed check: For each bot with weapon_gap, if a buy option exists, price is under mesos and level is met, and the bot is not shopping, queue the purchase.
- **Bandits are locked out of the best dagger by DEX and nothing in the stat plan fixes it.** (SipsBuddy46, SipsBuddy47, SipsBuddy48, SipsBuddy49, SipsBuddy50)
  - Evidence: Gephart (attack 50, 375,000 mesos) needs DEX 70; the bots have 25 to 30 DEX. All five hold 1.7M to 3.3M mesos, wear lv12 to 15 daggers, and carry weapon_gap.
  - Proposed check: If best affordable weapon it cannot wear is set and the stat gap exceeds 20, flag for an AP or weapon plan review.
- **Bots sit on maps where the snapshot lists much higher EXP per second options.** (SipsBuddy25, SipsBuddy26, SipsBuddy46, SipsBuddy48, SipsBuddy50)
  - Evidence: Current map EXP is 8.7 to 10.5 (Dark Stump, Cynical Orange Mushroom, Pig) versus options of 19 to 40.7 EXP/s with the same weapon.
  - Proposed check: If the current map option EXP is under half the best option EXP and the bot is not on a drop quest target, recommend a move.

## Fleet notes

- Three spearmen share the Zeco buy (225,000 mesos); split them across 103000200 and 105040200 to avoid crowding. (SipsBuddy29, SipsBuddy30, SipsBuddy34)
- 682010201 gets three bots on 31 Rotting Skeletons; Killa Bee 600020200 takes the overflow. (SipsBuddy16, SipsBuddy26, SipsBuddy33)
- Five bots reached cap 43 and stay idle by design. (SipsBuddy12, SipsBuddy37, SipsBuddy42, SipsBuddy44, SipsBuddy45)
- Idle capped thieves sit on Ludibrium maps 220010800 and 220010300 that active bots also use. (SipsBuddy44, SipsBuddy45, SipsBuddy42)
- These bandits farmed at 8.7 EXP/s for drops or travel while 17 to 22 EXP/s maps were listed. (SipsBuddy48, SipsBuddy50, SipsBuddy46)

## Plan per bot

| Bot | Lv | Job | Role | GrindAdvisor now | Strategist | Reason | Conf. | Check |
|---|---|---|---|---|---|---|---|---|
| SipsBuddy25 | 42 | spearman (warrior class) | Quest farmer B02 | training to lv43; farming Maple Impaler from Dark Stump at map 101020003 - best gear odds  | move to Hunting Ground in the Deep Forest II | Same Nakamaki earns 40.7 EXP/s on Curse Eye versus 10.5 on Dark Stump. | high | ok |
| SipsBuddy26 | 29 | warrior | Quest farmer B03 | training to lv43; im at map 102020000, heading to map 103020200 to grinding Orange Mushroo | buy Two-Handed Axe | 22,000 mesos is affordable and the axe earns 30.3 EXP/s versus 10.5 now. | high | ok |
| SipsBuddy29 | 42 | spearman (warrior class) | Quest farmer B06 | training to lv43; farming Maple Impaler from Wild Boar at map 101030001 - i really want th | buy Zeco | Fish Spear is 22 levels under the bot; Zeco costs 225,000 of 4.7M mesos and lifts EXP from 24.4 to 40.7. | high | ok |
| SipsBuddy30 | 41 | spearman (warrior class) | Quest farmer B07 | training to lv43; im at map 600000000, heading to map 105040200 to farming Black Martial A | buy Zeco | Weapon gap with 8.2M mesos; Zeco earns 40.7 EXP/s on its current destination. | high | ok |
| SipsBuddy31 | 35 | spearman (warrior class) | Quest farmer B08 | training to lv43; farming Fish Spear from Bubbling at map 103000101 - i really want that d | buy Nakamaki | 175,000 of 647,686 mesos lifts EXP from 15.1 to 34.9 on Killa Bee. | high | ok |
| SipsBuddy33 | 37 | spearman (warrior class) | Quest farmer B10 | training to lv43; im at map 101000300, heading to map 260010501 to grinding Scarf Plead -  | buy Nakamaki | Affordable at 1.27M mesos and earns 43.0 EXP/s on Rotting Skeleton versus 34.9 on Killa Bee with the Fish Spear. | high | ok |
| SipsBuddy34 | 40 | spearman (warrior class) | Quest farmer B11 | training to lv43; farming Scroll for Spear for Accuracy 60% from Wooden Mask at map 101030 | buy Zeco | Weapon gap with 3.2M mesos; Zeco earns 40.7 EXP/s on Jr. Wraith, which has 83 mobs and room. | medium | ok |
| SipsBuddy36 | 34 | spearman (warrior class) | Quest farmer B13 | training to lv43; im at map 100050000, heading to map 100040003 to grinding Dejected Green | buy Forked Spear | 60,000 mesos lifts EXP to 32.0 on Boomer versus 15.1 on Green Mushroom. | medium | ok |
| SipsBuddy41 | 42 | assassin (thief class) | Quest farmer C04 | training to lv43; im at map 682010200, heading to map 682010202 to grinding Dead Scarecrow | move to Soul Corridor | Dead Scarecrow is lv50 against a lv42 bot with attack 19 and is not among its options; Killa Bee gives 24.6. | low | ok |
| SipsBuddy46 | 39 | bandit (thief class) | Quest farmer C09 | training to lv43; grinding Cynical Orange Mushroom at map 100010100 - good exp | move to Line 2 <Area 1> | Jr. Necki gives 22.1 EXP/s versus 8.7 on Cynical Orange Mushroom. | high | ok |
| SipsBuddy47 | 39 | bandit (thief class) | Quest farmer C10 | training to lv43; im at map 105030000, heading to map 100010100 to grinding Cynical Orange | move to Deep Forest | Already on Deep Forest at 20.3 EXP/s; cancel the trip to the 8.7 EXP/s map. | high | move to the map it is already on or heading to |
| SipsBuddy48 | 36 | bandit (thief class) | Quest farmer C11 | training to lv43; im at map 104020000, heading to map 103020100 to farming Dark Nightshift | move to Soul Corridor | Killa Bee gives 19.0 EXP/s versus 8.7 on Pig and Ribbon Pig maps. | medium | ok |
| SipsBuddy50 | 36 | bandit (thief class) | Quest farmer C13 | training to lv43; grinding Pig at map 103020000 - solid exp for me | move to Deep Forest | Zombie Mushroom gives 17.2 EXP/s versus 8.7 on Pig. | high | ok |
| SipsBuddy1 | 51 | ice/lightning wizard (magician class) | Main farmer, Blizzard, map c | uncapped farming; grinding Roloduck at map 220011000 - at Orbis Station Enterence, resuppl | keep | Uncapped main farmer is resupplied and returning to its map. | high | ok |
| SipsBuddy10 | 36 | cleric (magician class) | Quest farmer A07 | training to lv43; im at map 220000110, heading to map 600010300 to farming Lightning Earri | keep | Mid travel to a Boomer map. | high | ok |
| SipsBuddy11 | 40 | cleric (magician class) | Quest farmer A08 | training to lv43; grinding Brown Teddy at map 220010600 - fast levels here | keep | Working map, no better option listed. | medium | ok |
| SipsBuddy12 | 43 | cleric (magician class) | Quest farmer A09 | training target reached; im at map 100000000, idle rn | keep | Cap 43 reached, idle by design. | high | ok |
| SipsBuddy16 | 41 | cleric (magician class) | Quest farmer A13 | training to lv43; im at map 600010005, heading to map 682010201 to grinding Rotting Skelet | keep | Mid travel to Rotting Skeleton, a reasonable level match. | medium | ok |
| SipsBuddy2 | 47 | cleric (magician class) | Holy Symbol, Genesis, suppor | uncapped farming; im at map 220010600, heading to map 220010400 to grinding Pink Teddy - g | keep | Uncapped support is travelling to a Pink Teddy map. | high | ok |
| SipsBuddy28 | 39 | spearman (warrior class) | Quest farmer B05 | training to lv43; im at map 221024000, heading to map 220010500 to farming Yellow Briggon  | keep | Mid travel on a drop chase, and 682010201 is already getting crowded. | low | ok |
| SipsBuddy3 | 49 | cleric (magician class) | Second Holy Symbol support c | training to lv81; grinding Brown Teddy at map 220010700 - solid exp for me | keep | Training to cap 81 on a working map. | medium | ok |
| SipsBuddy32 | 33 | spearman (warrior class) | Quest farmer B09 | training to lv43; im at map 103000100, heading to map 600020200 to grinding Killa Bee - go | keep | Mid travel to Killa Bee, a good option with no gain from buying. | medium | ok |
| SipsBuddy35 | 32 | spearman (warrior class) | Quest farmer B12 | training to lv43; im at map 600000000, heading to map 101020008 to grinding Horny Mushroom | keep | No listed option beats its current plan by a clear margin. | low | ok |
| SipsBuddy37 | 43 | spearman (warrior class) | Quest farmer B14 | training target reached; im at map 101030001, idle rn | keep | Cap 43 reached, idle by design. | high | ok |
| SipsBuddy38 | 42 | assassin (thief class) | Quest farmer C01 | training to lv43; im at map 220000110, heading to map 103000200 to grinding Jr. Wraith - s | keep | Mid travel to Jr. Wraith, which has 83 mobs. | medium | ok |
| SipsBuddy39 | 38 | assassin (thief class) | Quest farmer C02 | training to lv43; im at map 106000300, heading to map 105070200 to grinding Evil Eye - goo | keep | Mid travel to a map with 41 Evil Eye and a listed 28.2 EXP/s option. | medium | ok |
| SipsBuddy42 | 43 | assassin (thief class) | Quest farmer C05 | training target reached; im at map 220010300, idle rn | keep | Cap 43 reached, idle by design. | high | ok |
| SipsBuddy44 | 43 | assassin (thief class) | Quest farmer C07 | training target reached; im at map 220010800, idle rn | keep | Cap 43 reached, idle by design. | high | ok |
| SipsBuddy45 | 43 | assassin (thief class) | Quest farmer C08 | training target reached; im at map 220010800, idle rn | keep | Cap 43 reached, idle by design. | high | ok |
| SipsBuddy49 | 38 | bandit (thief class) | Quest farmer C12 | training to lv43; im at map 103000100, heading to map 600010100 to farming Scroll for Dagg | keep | Mid travel to Urban Fungus at 21.5 EXP/s. | medium | ok |
| SipsBuddy6 | 39 | cleric (magician class) | Quest farmer A03 | training to lv43; grinding Pink Teddy at map 220010200 - fast levels here | keep | Working Pink Teddy map, no better option listed. | medium | ok |
| SipsBuddy7 | 41 | cleric (magician class) | Quest farmer A04 | training to lv43; farming Sapphire Earrings from Pink Teddy at map 220010100 - best gear o | keep | Working map with a gear drop chase. | medium | ok |
| SipsBuddy8 | 41 | cleric (magician class) | Quest farmer A05 | training to lv43; im at map 220020400, heading to map 220010300 to grinding Pink Teddy - s | keep | Mid travel to a Pink Teddy map. | high | ok |
