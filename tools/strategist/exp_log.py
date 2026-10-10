#!/usr/bin/env python3
"""Append one row per online bot to the EXP log: time, bot, level, total EXP, map, state.

install_exp_log_agent.sh copies this script and the EXP table to ~/Library/Application Support/cosmic-explog
and runs it every 5 minutes from there (macOS blocks LaunchAgents from reading ~/Documents).
exp_report.py turns the log into EXP per hour per bot, before and after a change. Read only.
"""
import csv
import json
import os
import re
import time
import urllib.request
from pathlib import Path

DASH = "http://127.0.0.1:8089"
HOME_DIR = Path(os.environ.get("EXPLOG_DIR", Path.home() / "Library/Application Support/cosmic-explog"))
OUT = HOME_DIR / "exp-log.csv"
FIELDS = ["ts", "id", "name", "level", "total_exp", "map", "grinding", "errand"]


def exp_table():
    copied = Path(__file__).resolve().parent / "exp_table.json"
    if copied.exists():
        return json.loads(copied.read_text())
    java = Path(__file__).resolve().parents[2] / "src/main/java/constants/game/ExpTable.java"
    return [int(x) for x in re.search(r"exp = \{([^}]*)\}", java.read_text()).group(1).split(",")]


def get(path):
    with urllib.request.urlopen(DASH + path, timeout=30) as r:
        return json.load(r)


def main():
    cumulative = [0]
    for need in exp_table():
        cumulative.append(cumulative[-1] + need)
    rows = []
    now = int(time.time())
    for b in get("/api/botdebug")["bots"]:
        d = get(f"/api/botdebug?id={b['id']}")["bots"][0]
        level = d["lvl"]
        rows.append({"ts": now, "id": d["id"], "name": d["n"], "level": level,
                     "total_exp": cumulative[level - 1] + max(0, d["detail"]["exp"]),
                     "map": d["map"], "grinding": int(bool(d["grinding"])), "errand": d["errand"]})
    OUT.parent.mkdir(parents=True, exist_ok=True)
    new = not OUT.exists()
    with OUT.open("a", newline="") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS)
        if new:
            w.writeheader()
        w.writerows(rows)
    print(f"{len(rows)} bots -> {OUT}")


if __name__ == "__main__":
    main()
