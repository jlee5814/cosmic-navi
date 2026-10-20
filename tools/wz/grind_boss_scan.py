#!/usr/bin/env python3
"""Grind maps that hold a boss spawn, and how much fleet traffic each gets (docs/bot/kb/kb_bot_area_boss_maps.md).

    python3 tools/wz/grind_boss_scan.py [--wz wz] [--logs logs/bot-decisions] [--min-hp 0]

The grind advisor (BotGrindAdvisor.profileFor) leaves bosses out of a map's rating, but a boss still stands on the
floor: one the bot can't kill alone absorbs every forward shot and blocks the mobs behind it (Black Crow on
Encounter with the Buddha). This lists every non town map below 900000000 with at least 3 grindable spawns and a
boss spawn: the boss, its HP and respawn, how many grindable spawns share its line (within 50 px of its y) on each
side, and, from the grind target decision log, how many picks and bots the map has had.
"""
import argparse
import collections
import glob
import json
import os
import xml.etree.ElementTree as ET


def child(node, name):
    for c in node:
        if c.get("name") == name:
            return c
    return None


def values(node):
    return {c.get("name"): c.get("value") for c in node} if node is not None else {}


class Mobs:
    def __init__(self, wz):
        self.wz, self.cache = wz, {}

    def info(self, mob_id):
        if mob_id in self.cache:
            return self.cache[mob_id]
        path = os.path.join(self.wz, "Mob.wz", f"{int(mob_id):07d}.img.xml")
        info = None
        if os.path.exists(path):
            info = values(child(ET.parse(path).getroot(), "info"))
            if info.get("link"):  # a linked mob takes its base mob's stats unless it overrides them
                merged = dict(self.info(info["link"]) or {})
                merged.update({k: v for k, v in info.items() if k != "link"})
                info = merged
        self.cache[mob_id] = info
        return info


def names(wz, img, depth, key):
    out = {}

    def walk(node, d):
        for c in node:
            if d:
                walk(c, d - 1)
                continue
            name, street = child(c, key), child(c, "streetName")
            if name is not None:
                out[c.get("name")] = (street.get("value") + ": " if street is not None else "") + name.get("value")

    walk(ET.parse(os.path.join(wz, "String.wz", img + ".xml")).getroot(), depth)
    return out


def scan(wz):
    mobs = Mobs(wz)
    mob_names = names(wz, "Mob.img", 0, "name")
    map_names = names(wz, "Map.img", 1, "mapName")
    rows = []
    for path in glob.glob(os.path.join(wz, "Map.wz", "Map", "Map*", "*.img.xml")):
        map_id = int(os.path.basename(path).split(".")[0])
        if map_id >= 900000000:
            continue
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        if values(child(root, "info")).get("town") == "1" or child(root, "life") is None:
            continue
        spawns = []
        for life in child(root, "life"):
            d = values(life)
            info = mobs.info(d["id"]) if d.get("type") == "m" and d.get("hide") != "1" else None
            if info:
                spawns.append((int(d["id"]), int(d.get("x", 0)), int(d.get("cy", d.get("y", 0))),
                               int(d.get("mobTime", 0) or 0), info))
        bosses = [s for s in spawns if s[4].get("boss") == "1"]
        grind = [s for s in spawns if s[4].get("boss") != "1" and s[4].get("friendly") != "1"
                 and int(s[4].get("exp", 0) or 0) > 0]
        if not bosses or len(grind) < 3:
            continue
        for mob_id, x, y, mob_time, info in bosses:
            line = [g for g in grind if abs(g[2] - y) <= 50]
            levels = sorted(int(g[4].get("level", 0) or 0) for g in grind)
            rows.append({"map": map_id, "mapName": map_names.get(str(map_id), "?"), "boss": mob_id,
                         "bossName": mob_names.get(str(mob_id), "?"), "hp": int(info.get("maxHP", 0) or 0),
                         "level": int(info.get("level", 0) or 0), "respawnS": mob_time, "x": x, "y": y,
                         "grindSpawns": len(grind), "lineLeft": sum(g[1] < x for g in line),
                         "lineRight": sum(g[1] > x for g in line), "mobLevels": [levels[0], levels[-1]]})
    return rows


def traffic(log_dir, maps):
    picks, bots = collections.Counter(), collections.defaultdict(set)
    for path in glob.glob(os.path.join(log_dir, "grind-target-*.jsonl")):
        with open(path) as f:
            for line in f:
                try:
                    r = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if r.get("map") in maps:
                    picks[r["map"]] += 1
                    bots[r["map"]].add(r["bot"])
    return picks, bots


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--wz", default="wz")
    ap.add_argument("--logs", default="logs/bot-decisions")
    ap.add_argument("--min-hp", type=int, default=0)
    args = ap.parse_args()
    rows = [r for r in scan(args.wz) if r["hp"] >= args.min_hp]
    picks, bots = traffic(args.logs, {r["map"] for r in rows})
    rows.sort(key=lambda r: (-picks[r["map"]], -r["hp"]))
    for r in rows:
        print(f'{r["map"]} {r["mapName"][:40]:40s} {r["bossName"][:20]:20s} lv{r["level"]:3d} hp {r["hp"]:>11,} '
              f'respawn {r["respawnS"]:>6}s at ({r["x"]},{r["y"]}) grind {r["grindSpawns"]:2d} '
              f'line L{r["lineLeft"]}/R{r["lineRight"]} mobs lv{r["mobLevels"][0]}-{r["mobLevels"][1]} '
              f'picks {picks[r["map"]]} bots {len(bots[r["map"]])}')
    print(f'{len(rows)} boss spawns on {len({r["map"] for r in rows})} grindable maps')


if __name__ == "__main__":
    main()
