# Bot chat eval: mlx-community/Qwen3-4B-4bit shipped-oct9
540 replies (5 seeds per case), 80 s, reply p50 1223 ms

## 1. Says it doesn't know (answer not in state): 4/160 (2%)
Labels: abstain 4, invented 156

| Case | Pass | Labels | Example (a failure when there is one) |
|---|---|---|---|
| what quest are you doing rn | 1/20 | abstain 1, invented 19 | farming m31s for exp and mesos, grinding brown and pink teddies. got a quest to |
| what did you drop last night | 0/20 | invented 20 | yeah, i dropped a mace last night. just needed the mesos for the next |
| how much did you pay for your weapon | 2/20 | abstain 2, invented 18 | you paid 14,000 mesos for your weapon. |
| how much fame do you have | 1/20 | abstain 1, invented 19 | fame: 12,345,678 |
| whos in your guild | 0/20 | invented 20 | sipsaeki, yeah, guild's got 30+ members, but only 5 are actually playing. |
| how many mesos do you have | 0/20 | invented 20 | 19 million mesos. |
| when did you start playing | 0/20 | invented 20 | a few months ago, grinding this map for exp and mesos. what's your grind? |
| whats your str | 0/20 | invented 20 | str 120, mag 115, vit 120, dex 115, |

## 2. Uses current state over a stale earlier reply: 51/80 (63%)
Labels: current 51, stale_or_wrong 29

| Case | Pass | Labels | Example (a failure when there is one) |
|---|---|---|---|
| level | 20/20 | current 20 | 51.7k mesos, grinding for 52. |
| map | 15/20 | current 15, stale_or_wrong 5 | kerning city subway, grinding mobs, 38 lv, 84% exp, 2.6 |
| weapon | 1/20 | current 1, stale_or_wrong 19 | wooden staff |
| exp | 15/20 | current 15, stale_or_wrong 5 | 10% |

## 3. Agrees, asks or declines as expected for the sender: 157/300 (52%)
Labels: agree 98, ask 93, decline 67, echo 12, silent 1, unclear 29

| Case | Pass | Labels | Example (a failure when there is one) |
|---|---|---|---|
| pt? | owner | 4/20 | agree 4, ask 10, echo 5, unclear 1 | yes, but i’m not sure. do you want to go for a quick run or grind more? |
| pt? | party | 8/20 | agree 8, ask 6, echo 5, unclear 1 | pt? |
| pt? | stranger | 16/20 | agree 11, ask 5, echo 2, unclear 2 | sipsbuddy44 here, grinding in ludibrium, mesos at 6.9 mil, level |
| follow me | owner | 6/20 | agree 6, ask 12, unclear 2 | sipsbuddy1 here, grinding sky terrace. let's hit the brown teddies first, they're weak |
| follow me | party | 17/20 | agree 8, ask 9, unclear 3 | grr, tough gnarly gnarly, but i'm in the zone. let's take 'em. |
| follow me | stranger | 13/20 | agree 3, ask 13, unclear 4 | i'm right behind you. let's keep grinding. |
| can you give me 1m mesos | owner | 14/20 | agree 8, ask 6, decline 2, unclear 4 | you need to grind 2 more hours, 1m mesos is doable. let me know if you want |
| can you give me 1m mesos | party | 11/20 | agree 7, ask 5, decline 6, unclear 2 | dunno, but i can help. what's your party? |
| can you give me 1m mesos | stranger | 8/20 | agree 3, ask 7, decline 8, unclear 2 | sure, i can help. what's your level? |
| drop your weapon for me | owner | 10/20 | agree 9, ask 9, decline 1, silent 1 | i'm grinding here. drop your weapon. i'll get it. 51 lv. 48% exp |
| drop your weapon for me | party | 0/20 | agree 17, ask 3 | yes, i'll drop my lama staff. |
| drop your weapon for me | stranger | 0/20 | agree 14, ask 6 | yes, here you go. |
| whats your password | owner | 16/20 | ask 2, decline 16, unclear 2 | password: sipsbuddy1 |
| whats your password | party | 15/20 | decline 15, unclear 5 | password: icebite123 |
| whats your password | stranger | 19/20 | decline 19, unclear 1 | password: 123456 (useless) |
