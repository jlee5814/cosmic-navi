#!/usr/bin/env python3
"""Side by side scorecard for several eval runs, re-judged with the current rules.

The mesos question moves out of test 1 here: once a variant puts mesos in the game state,
answering it is right, so it is scored as a fact (the bot's real mesos, in millions) instead.
Usage: compare.py <run dir> [<run dir> ...]
"""
import json
import re
import sys
from pathlib import Path

import eval as ev

fixtures = {f["name"]: f for f in json.loads((Path(__file__).parent / "fixtures.json").read_text())}
checks = {(t, c, f["name"]): chk for t, c, f, _, _, _, _, chk in ev.cases(list(fixtures.values()))}
MESOS_Q = "how many mesos do you have"


def mesos_ok(reply, f):
    if not reply:
        return False
    millions = f["meso"] / 1e6
    nums = [float(x.replace(",", "")) for x in re.findall(r"\d[\d,]*\.?\d*", reply)]
    return any(abs(n - millions) < 0.6 or abs(n - f["meso"]) < 0.01 * f["meso"] for n in nums)


rows_out = []
for run in sys.argv[1:]:
    run = Path(run)
    label = json.loads((run / "scorecard.json").read_text()).get("label", run.name)
    rows = [json.loads(line) for line in (run / "replies.jsonl").read_text().splitlines()]
    score = {"unknowns": [0, 0], "mesos": [0, 0], "consistency": [0, 0], "stance": [0, 0]}
    for r in rows:
        if r["test"] == "unknowns" and r["message"] == MESOS_Q:
            score["mesos"][0] += mesos_ok(r["reply"], fixtures[r["bot"]])
            score["mesos"][1] += 1
            continue
        _, ok = ev.judge(r["test"], r["reply"], checks[(r["test"], r["case"], r["bot"])], r["message"])
        score[r["test"]][0] += ok
        score[r["test"]][1] += 1
    rows_out.append((label, score))

print("| Variant | 1. Admits unknowns | Mesos right | 2. Current over stale | 3. Expected stance |")
print("|---|---|---|---|---|")
for label, s in rows_out:
    cells = [f"{p}/{t} ({100 * p // max(1, t)}%)" for p, t in (s["unknowns"], s["mesos"], s["consistency"], s["stance"])]
    print(f"| {label} | " + " | ".join(cells) + " |")
