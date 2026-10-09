#!/usr/bin/env python3
"""Snapshot the bot fleet for the strategist: every roster bot, the maps they use, and their gear.

Read-only: the dashboard API (/api/botdebug, /api/mapinfo), MySQL for equipped items, and the WZ
name files. Usage: fleet_snapshot.py <out.json>
"""
import json
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "llm-bench/bot_eval"))
import make_fixtures as mf  # noqa: E402  (WZ name lookups and the EXP table)
from prompt import job_label  # noqa: E402

DASH = "http://127.0.0.1:8089"


def get(path):
    with urllib.request.urlopen(DASH + path, timeout=30) as r:
        return json.load(r)


def gear_by_bot(ids, eqp_names):
    sql = ("SELECT characterid, itemid FROM cosmic.inventoryitems WHERE inventorytype=-1 AND position>-100 "
           f"AND characterid IN ({','.join(str(int(i)) for i in ids)}) ORDER BY characterid, position DESC")
    out = subprocess.run(["docker", "exec", "cosmic-local-db-1", "mysql", "-uroot", "-pcosmic-local", "-N", "-e", sql],
                         capture_output=True, text=True).stdout
    gear = {}
    for line in out.splitlines():
        cid, item = map(int, line.split())
        gear.setdefault(cid, []).append(eqp_names.get(item, f"item {item}"))
    return gear


def main():
    # The list endpoint omits per-bot detail (EXP, mesos, skills); fetch each bot.
    bots = [get(f"/api/botdebug?id={b['id']}")["bots"][0] for b in get("/api/botdebug")["bots"]]
    names, eqp = mf.map_names(), mf.string_names("Eqp.img.xml")
    gear = gear_by_bot([b["id"] for b in bots], eqp)
    map_ids = sorted({m for b in bots for m in (b["map"], b["dst"]) if m and m > 0})
    maps = {}
    for mid in map_ids:
        info = get(f"/api/mapinfo?id={mid}")
        name, street = names.get(mid, (str(mid), ""))
        maps[mid] = {"id": mid, "name": name, "street": street,
                     "mobs": [{"name": m["name"], "level": m["level"], "spawns": m["spawns"]} for m in info.get("mobs", [])]}
    fleet = []
    for b in bots:
        d, lvl = b["detail"], b["lvl"]
        fleet.append({
            "id": b["id"], "name": b["n"], "level": lvl, "job": job_label(d["job"]), "planned_job": b["plannedJob"],
            "role": b["role"], "training_cap": b["trainingTarget"] or None, "cap_reached": bool(b["trainingComplete"]),
            "exp_pct": min(99, 100 * max(0, d["exp"]) // mf.EXP_TABLE[lvl - 1]) if lvl < 200 else None,
            "meso": d["meso"], "map": b["map"], "heading_to": b["dst"] if b["dst"] and b["dst"] > 0 else None,
            "party": b["party"] or None, "grinding": b["grinding"],
            # What GrindAdvisor and the roster chose; the strategist is graded against this.
            "advisor_status": re.sub(r"^assigned: [^;]*; ", "", b["status"]),
            "gear": gear.get(b["id"], []),
            "top_skills": sorted(((int(k), v) for k, v in d.get("skills", {}).items() if v > 0 and int(k) >= 10000),
                                 key=lambda kv: -kv[1])[:4],
        })
    skill_names = mf.string_names("Skill.img.xml")
    for f in fleet:
        f["top_skills"] = [f"{skill_names.get(k, k)} {v}" for k, v in f["top_skills"]]
    out = {"taken_at": time.strftime("%Y-%m-%d %H:%M"), "bots": fleet, "maps": list(maps.values())}
    Path(sys.argv[1]).write_text(json.dumps(out, indent=1))
    print(f"{len(fleet)} bots, {len(maps)} maps -> {sys.argv[1]}")


if __name__ == "__main__":
    main()
