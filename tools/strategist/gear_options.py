"""Weapon facts and computed EXP per second options for the fleet snapshot.

The strategist should read numbers, not recall them: a small model chose right only once the options
came with EXP per second worked out (tools/strategist/runs/reading-test). This module mirrors the
server's physical damage formulas (Character.calculateMaxBaseDamage, CombatFormulaProvider defense,
BotGrindAdvisor's 0.72 s attack cycle) closely enough to rank options. It is an estimate: it assumes
every hit lands, about 1 s of walking per kill and no other bots on the map, caps kills at what the
spawn points refill, and skips magicians (magic damage is different). A shop weapon counts only if the
bot meets its level, job and stat requirements today.
"""
import subprocess
import xml.etree.ElementTree as ET
from functools import lru_cache
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WZ = ROOT / "wz"
ATTACK_CYCLE_S = 0.72
SEEK_S = 1.0
RESPAWN_S = 10.0
GAP_LEVELS = 20             # flag a weapon this many levels below the bot...
GAP_MIN_MESO = 1_000_000    # ...when the bot can clearly afford better

# ItemInformationProvider.getWeaponType: category = (itemId / 10000) % 100, multiplier from WeaponType.
WEAPON_MULT = {30: 4.0, 31: 4.4, 32: 4.4, 33: 4.0, 37: 3.6, 38: 3.6, 40: 4.6, 41: 4.8, 42: 4.8,
               43: 5.0, 44: 5.0, 45: 3.4, 46: 3.6, 47: 3.6, 48: 4.8, 49: 3.6}
MAGIC = {37, 38}
JOB_BIT = {1: 1, 2: 2, 3: 4, 4: 8, 5: 16}  # job // 100 -> WZ reqJob bit (warrior, magician, bowman, thief, pirate)


def _children(node):
    return {c.get("name"): c for c in node}


def _values(node):
    return {c.get("name"): c.get("value") for c in node if c.get("value") is not None}


@lru_cache(maxsize=None)
def weapon_info(item_id):
    path = WZ / f"Character.wz/Weapon/0{item_id}.img.xml"
    if not path.exists():
        return None
    info = _values(_children(ET.parse(path).getroot())["info"])
    num = lambda k: int(info.get(k, 0) or 0)  # noqa: E731
    return {"req_level": num("reqLevel"), "attack": num("incPAD"), "req_job": num("reqJob"),
            "req": {"str": num("reqSTR"), "dex": num("reqDEX"), "int": num("reqINT"), "luk": num("reqLUK")}}


@lru_cache(maxsize=None)
def _mob_ids_by_name():
    out = {}
    for node in ET.parse(WZ / "String.wz/Mob.img.xml").getroot():
        name = _values(node).get("name")
        if name and node.get("name", "").isdigit():
            out.setdefault(name, []).append(int(node.get("name")))
    return out


@lru_cache(maxsize=None)
def mob_stats(mob_id):
    path = WZ / f"Mob.wz/{mob_id:07d}.img.xml"
    if not path.exists():
        return None
    info = _values(_children(ET.parse(path).getroot())["info"])
    num = lambda k: int(info.get(k, 0) or 0)  # noqa: E731
    return {"level": num("level"), "hp": num("maxHP"), "wdef": num("PDDamage"), "exp": num("exp")}


def mob_by_name(name, level):
    for mid in _mob_ids_by_name().get(name, []):
        s = mob_stats(mid)
        if s and s["level"] == level and s["hp"] > 0:
            return s
    return None


@lru_cache(maxsize=None)
def _skill_levels(skill_id):
    path = WZ / f"Skill.wz/{skill_id // 10000}.img.xml"
    if not path.exists():
        return {}
    for node in ET.parse(path).getroot().iter("imgdir"):
        if node.get("name") == str(skill_id):
            level = _children(node).get("level")
            return {int(l.get("name")): _values(l) for l in level} if level is not None else {}
    return {}


def skill_level_values(skill_id, level):
    return _skill_levels(skill_id).get(level, {})


def mastery(skills):
    """Base 10%, raised by the best mastery skill the bot has (WZ mastery m -> (5m + 10)%)."""
    best = 0.1
    for sid, lv in skills.items():
        m = skill_level_values(int(sid), lv).get("mastery")
        if m is not None:
            best = max(best, (5 * int(m) + 10) / 100)
    return best


def attack_profile(detail, atk_skill):
    """(damage fraction per line, lines per attack) of the bot's main attack; a basic swing if none."""
    lv = detail.get("skills", {}).get(str(atk_skill), 0) if atk_skill else 0
    vals = skill_level_values(atk_skill, lv) if lv else {}
    pct = int(vals.get("damage", 100)) / 100
    lines = int(vals.get("attackCount", vals.get("bulletCount", 1)))
    return pct, max(1, lines)


def stats_for(category, job, d):
    """(main stat, secondary stat) as Character.calculateMaxBaseDamage picks them."""
    thief = job // 100 == 4
    if category in (45, 46, 49):
        return d["dex"], d["str"]
    if category == 47 or (category == 33 and thief):
        return d["luk"], d["dex"] + d["str"]
    return d["str"], d["dex"]


def exp_per_second(category, job, d, total_watk, m, pct, lines, mob, bot_level):
    mult = 3.6 if (category == 33 and job // 100 == 4) else WEAPON_MULT[category]
    main, sec = stats_for(category, job, d)
    hi = (mult * main + sec) / 100 * total_watk
    lo = (mult * main * 0.9 * m + sec) / 100 * total_watk
    factor = 1.0 - 0.01 * max(0, mob["level"] - bot_level)
    hi_line = max(1.0, hi * pct * factor - mob["wdef"] * 0.5)
    lo_line = min(hi_line, max(1.0, lo * pct * factor - mob["wdef"] * 0.6))
    per_attack = lines * (hi_line + lo_line) / 2
    attacks = max(1, -(-mob["hp"] // max(1, int(per_attack))))
    return mob["exp"] / (attacks * ATTACK_CYCLE_S + SEEK_S), attacks


def worn_weapons(ids):
    """char id -> (item id, scrolled weapon attack) of the equipped weapon."""
    sql = ("SELECT i.characterid, i.itemid, e.watk FROM cosmic.inventoryitems i JOIN cosmic.inventoryequipment e "
           "ON e.inventoryitemid = i.inventoryitemid WHERE i.inventorytype = -1 AND i.position = -11 "
           f"AND i.characterid IN ({','.join(str(int(i)) for i in ids)})")
    out = subprocess.run(["docker", "exec", "cosmic-local-db-1", "mysql", "-uroot", "-pcosmic-local", "-N", "-e", sql],
                         capture_output=True, text=True).stdout
    return {int(c): (int(i), int(w)) for c, i, w in (line.split() for line in out.splitlines())}


def shop_weapons(npc_names):
    """Every weapon an NPC shop sells: (item id, price, npc name), cheapest listing per item."""
    sql = ("SELECT si.itemid, si.price, s.npcid FROM cosmic.shopitems si JOIN cosmic.shops s ON s.shopid = si.shopid "
           "WHERE si.itemid BETWEEN 1300000 AND 1499999 AND si.price > 1")
    out = subprocess.run(["docker", "exec", "cosmic-local-db-1", "mysql", "-uroot", "-pcosmic-local", "-N", "-e", sql],
                         capture_output=True, text=True).stdout
    best = {}
    for line in out.splitlines():
        item, price, npc = map(int, line.split())
        if item not in best or price < best[item][0]:
            best[item] = (price, npc_names.get(npc, f"NPC {npc}"))
    return best


def wearable(info, level, job, d):
    bit = JOB_BIT.get(job // 100, 0)
    job_ok = info["req_job"] == 0 or (bit and info["req_job"] & bit)
    stats_ok = all(d.get(k, 0) >= v for k, v in info["req"].items())
    return info["req_level"] <= level and job_ok and stats_ok


def enrich(bot_debug, fleet_entry, maps, eqp_names, worn, shop):
    """Add weapon facts, the weapon gap flag and EXP per second options to one fleet entry."""
    d, level = bot_debug["detail"], bot_debug["lvl"]
    job = d["job"]
    flags = fleet_entry.setdefault("flags", [])
    if bot_debug["id"] not in worn:
        return
    item, scrolled = worn[bot_debug["id"]]
    info = weapon_info(item)
    if info is None:
        return
    category = (item // 10000) % 100
    fleet_entry["weapon"] = {"name": eqp_names.get(item, f"item {item}"), "req_level": info["req_level"],
                             "attack": scrolled, "levels_below_bot": level - info["req_level"]}
    if level - info["req_level"] >= GAP_LEVELS and d["meso"] >= GAP_MIN_MESO:
        flags.append("weapon_gap")
    if category in MAGIC or category not in WEAPON_MULT or fleet_entry.get("cap_reached"):
        return
    # Same weapon category as the worn one stands in for the bot's preferred weapon type.
    upgrades = []
    for cand, (price, npc) in shop.items():
        ci = weapon_info(cand)
        if (cand // 10000) % 100 != category or ci is None or price > d["meso"] or not wearable(ci, level, job, d):
            continue
        if ci["attack"] > scrolled:
            upgrades.append((ci["attack"], -price, cand, price, npc))
    best_buy = max(upgrades) if upgrades else None
    # The best weapon it could afford and has the level and job for, but not the stats: points at an AP
    # build that never raised the stat the next weapons need (the bandits sat at 25 to 30 DEX).
    blocked = []
    for cand, (price, npc) in shop.items():
        ci = weapon_info(cand)
        if (cand // 10000) % 100 != category or ci is None or price > d["meso"] or ci["attack"] <= scrolled:
            continue
        bit = JOB_BIT.get(job // 100, 0)
        if ci["req_level"] <= level and (ci["req_job"] == 0 or ci["req_job"] & bit) and not wearable(ci, level, job, d):
            blocked.append((ci["attack"], -price, cand, price, ci))
    if blocked and (not best_buy or max(blocked)[0] > best_buy[0]):
        att, _, cand, price, ci = max(blocked)
        fleet_entry["upgrade_blocked_by_stats"] = {
            "weapon": eqp_names.get(cand, cand), "attack": att, "price": price,
            "needs": {k: v for k, v in ci["req"].items() if v and d.get(k, 0) < v},
            "has": {k: d.get(k, 0) for k, v in ci["req"].items() if v and d.get(k, 0) < v}}
    m = mastery(d.get("skills", {}))
    pct, lines = attack_profile(d, bot_debug.get("atk") or 0)
    weapons = [("current " + fleet_entry["weapon"]["name"], d["watk"], None)]
    if best_buy:
        att, _, cand, price, npc = best_buy
        weapons.append((f"buy {eqp_names.get(cand, cand)} ({price:,} mesos from {npc})",
                        d["watk"] - scrolled + att, price))
    options = []
    for mp in maps:
        candidates = [(mob_by_name(x["name"], x["level"]), x) for x in mp["mobs"]]
        # Other maps only count mobs near the bot's level; the current map counts whatever it has.
        candidates = [(s, x) for s, x in candidates
                      if s and (mp["id"] == fleet_entry["map"] or level - 20 <= s["level"] <= level + 5)]
        for label, watk, price in weapons:
            best = None
            for s, x in candidates:
                eps, attacks = exp_per_second(category, job, d, watk, m, pct, lines, s, level)
                # A map can't supply more kills than its spawn points refill (every 10 s, as in
                # BotGrindPlanner.RESPAWN_PERIOD_SECONDS), however fast the bot kills.
                eps = min(eps, x["spawns"] * s["exp"] / RESPAWN_S)
                if best is None or eps > best[0]:
                    best = (eps, attacks, x["name"])
            if best:
                options.append({"weapon": label, "map": mp["id"], "mob": best[2],
                                "attacks_per_kill": best[1], "exp_per_s": round(best[0], 1)})
    options.sort(key=lambda o: -o["exp_per_s"])
    current = next((o for o in options if o["map"] == fleet_entry["map"] and o["weapon"].startswith("current")), None)
    top = options[:3]
    if current and current not in top:
        top.append(current)
    fleet_entry["options"] = top
