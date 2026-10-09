#!/usr/bin/env python3
"""Snapshot live bots into eval fixtures: the facts PromptBuilder would show the model.

Reads the bot dashboard (/api/botdebug, /api/mapinfo), the equipped items in MySQL and the
names in the WZ string files. Fixtures are frozen on purpose: an eval must replay the same
situation every run, while the live game keeps moving.
Usage: make_fixtures.py <out.json> <bot id> [<bot id> ...]
"""
import json
import re
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
DASH = "http://127.0.0.1:8089"
EXP_TABLE = [int(x) for x in re.search(r"exp = \{([^}]*)\}",
                                       (ROOT / "src/main/java/constants/game/ExpTable.java").read_text()).group(1).split(",")]


def get(path):
    with urllib.request.urlopen(DASH + path, timeout=20) as r:
        return json.load(r)


def string_names(img, depth_tag="name"):
    """id -> name from a String.wz image (any nesting depth)."""
    out = {}
    for node in ET.parse(ROOT / "wz/String.wz" / img).getroot().iter("imgdir"):
        if node.get("name", "").isdigit():
            for s in node.findall("string"):
                if s.get("name") == depth_tag:
                    out[int(node.get("name"))] = s.get("value")
    return out


def map_names():
    out = {}
    for node in ET.parse(ROOT / "wz/String.wz/Map.img.xml").getroot().iter("imgdir"):
        if node.get("name", "").isdigit():
            vals = {s.get("name"): s.get("value") for s in node.findall("string")}
            if "mapName" in vals:
                out[int(node.get("name"))] = (vals.get("mapName"), vals.get("streetName", ""))
    return out


def skill_max(skill_id):
    path = ROOT / f"wz/Skill.wz/{skill_id // 10000}.img.xml"
    for node in ET.parse(path).getroot().iter("imgdir"):
        if node.get("name") == str(skill_id):
            level = next((c for c in node.findall("imgdir") if c.get("name") == "level"), None)
            return len(level.findall("imgdir")) if level is not None else 0
    return 0


def equipped(char_id, eqp_names):
    sql = (f"SELECT itemid FROM cosmic.inventoryitems WHERE characterid={int(char_id)} "
           "AND inventorytype=-1 AND position>-100 ORDER BY position DESC")
    out = subprocess.run(["docker", "exec", "cosmic-local-db-1", "mysql", "-uroot", "-pcosmic-local", "-N", "-e", sql],
                         capture_output=True, text=True).stdout.split()
    return [eqp_names.get(int(i), f"item {i}") for i in out]


def main():
    out_path, ids = sys.argv[1], [int(x) for x in sys.argv[2:]]
    skills_by_id, eqp, maps = string_names("Skill.img.xml"), string_names("Eqp.img.xml"), map_names()
    fixtures = []
    for bot_id in ids:
        b = get(f"/api/botdebug?id={bot_id}")["bots"][0]
        d = b["detail"]
        lvl = b["lvl"]
        pct = min(99, 100 * max(0, d["exp"]) // EXP_TABLE[lvl - 1]) if lvl < 200 else -1
        name, street = maps.get(b["map"], ("", ""))
        mobs = get(f"/api/mapinfo?id={b['map']}").get("mobs", [])
        skills = sorted(((int(k), v) for k, v in d.get("skills", {}).items() if v > 0 and int(k) // 10000 != 0),
                        key=lambda kv: -kv[1])[:8]
        fixtures.append({
            "id": bot_id, "name": b["n"], "job_id": d["job"], "level": lvl, "exp_pct": pct, "meso": d["meso"],
            "map": name, "street": street,
            "status": "grinding" if b.get("grinding") else ("following owner" if b.get("following") else
                                                            "standing around, no orders"),
            "mobs": ", ".join(f"{m['name']} lv{m['level']} x{m['spawns']}" for m in mobs[:4]),
            "gear": equipped(bot_id, eqp),
            "skills": [f"{skills_by_id.get(k, f'skill {k}')} {v}/{skill_max(k)}" for k, v in skills],
        })
        print(f"{b['n']}: lv{lvl} job {d['job']} map {name} gear {len(fixtures[-1]['gear'])} skills {len(skills)}")
    Path(out_path).write_text(json.dumps(fixtures, indent=1))


if __name__ == "__main__":
    main()
