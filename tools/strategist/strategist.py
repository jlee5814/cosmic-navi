#!/usr/bin/env python3
"""Fleet strategist, read-only: asks a model for a plan per bot, fleet notes and new findings, then writes
a report comparing them with what GrindAdvisor and the roster chose. Nothing is sent to the game.

Usage: strategist.py <snapshot.json> <out dir> [--backend openai|claude] [--url ...] [--model sonnet]
  openai  any OpenAI compatible server (SGLang locally): one request per bot plus one for notes
  claude  the Claude Code CLI (`claude -p`) on the owner's Claude plan: one call for the whole fleet,
          with read only tools (the snapshot, WZ files, the bot dashboard API, a SELECT only MySQL user)
"""
import argparse
import json
import subprocess
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

SYSTEM = """You plan for a fleet of MapleStory (v83) bots owned by one player. Each bot already runs on its own:
GrindAdvisor picks where it grinds and what drops it chases, one bot at a time, and the bots buy weapons
only when they can wear them. You see the whole fleet, so look for what a single bot cannot see: a better
map, a party partner, crowding, a bot idle without reason, gear or stats holding a bot back.

Facts you must respect:
- Roles and training caps were set by the owner on purpose. Never suggest leveling past a training cap;
  a bot with its cap reached is idle by design, so keep it.
- Party EXP is shared only among members within 5 levels of each other.
- Only suggest maps from the map list, by id, and only buy items listed in that bot's options.
- Options carry EXP per second computed with the server's damage formulas. They are estimates that
  assume every hit lands and no other bots on the map; prefer the option with the highest EXP per
  second unless a fact in the snapshot says otherwise.
- A bot shopping, resupplying or travelling is mid task; keep it unless something is clearly wrong.
- When the current choice is reasonable, answer keep. Changing a working plan has a cost.

Flags falling, frozen and no_exp come from sampling each bot for a minute. Falling or frozen
is a movement bug, not a planning choice: keep the bot, and list it in a note so the owner can fix the
map. no_exp on a grinding bot can be a bad map or a stuck bot; say which the facts support.

Findings are for problems the owner has not coded a rule for yet: a pattern across bots, with the
evidence and a check that code could run to catch it every time. Report only what the facts show.

Be concrete and brief. Reasons are one short sentence."""

PLAN_SCHEMA = {
    "type": "object",
    "properties": {
        "action": {"type": "string", "enum": ["keep", "move", "party", "buy"]},
        "target_map": {"type": ["integer", "null"]},
        "partner": {"type": ["string", "null"]},
        "item": {"type": ["string", "null"]},
        "reason": {"type": "string", "maxLength": 200},
        "confidence": {"type": "string", "enum": ["low", "medium", "high"]},
    },
    "required": ["action", "target_map", "partner", "item", "reason", "confidence"],
}
NOTE_ITEMS = {
    "type": "object",
    "properties": {"note": {"type": "string", "maxLength": 240},
                   "bots": {"type": "array", "items": {"type": "string"}}},
    "required": ["note", "bots"]}
NOTES_SCHEMA = {
    "type": "object",
    "properties": {"notes": {"type": "array", "maxItems": 5, "items": NOTE_ITEMS}},
    "required": ["notes"],
}
FINDING_ITEMS = {
    "type": "object",
    "properties": {"pattern": {"type": "string", "maxLength": 240},
                   "bots": {"type": "array", "items": {"type": "string"}},
                   "evidence": {"type": "string", "maxLength": 400},
                   "proposed_check": {"type": "string", "maxLength": 300}},
    "required": ["pattern", "bots", "evidence", "proposed_check"]}


def map_line(m, occupants):
    mobs = ", ".join(f"{x['name']} lv{x['level']} x{x['spawns']}" for x in m["mobs"]) or "no mobs"
    where = f"{m['name']} ({m['street']})" if m["street"] and m["street"] != m["name"] else m["name"]
    who = ", ".join(occupants.get(m["id"], [])) or "none"
    return f"{m['id']} | {where} | {mobs} | bots here or heading here: {who}"


def bot_line(b):
    cap = f"cap {b['training_cap']}{' reached' if b['cap_reached'] else ''}" if b["training_cap"] else "no cap"
    line = (f"{b['name']} | lv{b['level']} {b['job']} | role: {b['role']} | {cap} | {b['meso']:,} mesos | "
            f"map {b['map']}{' -> ' + str(b['heading_to']) if b['heading_to'] else ''} | "
            f"party {b['party'] or 'none'} | now: {b['advisor_status']}")
    w = b.get("weapon")
    if w:
        line += f"\n    weapon: {w['name']} (level {w['req_level']}, attack {w['attack']}, {w['levels_below_bot']} levels below the bot)"
    if b.get("flags"):
        line += f"\n    flags: {', '.join(b['flags'])}"
    m = b.get("motion")
    if m and not m.get("changed_map"):
        line += (f"\n    last {m['seconds']} s: moved dx {m['dx']}, dy {m['dy']}, "
                 f"EXP {'+' + str(m['exp_gain']) if m['exp_gain'] is not None else 'leveled up'}")
    blocked = b.get("upgrade_blocked_by_stats")
    if blocked:
        needs = ", ".join(f"{k.upper()} {v} (has {blocked['has'][k]})" for k, v in blocked["needs"].items())
        line += (f"\n    best affordable weapon it cannot wear: {blocked['weapon']} (attack {blocked['attack']}, "
                 f"{blocked['price']:,} mesos), needs {needs}")
    for o in b.get("options", []):
        line += (f"\n    option: {o['weapon']} on map {o['map']} ({o['mob']}): "
                 f"{o['attacks_per_kill']} attacks per kill, {o['exp_per_s']} EXP/s")
    return line


def fleet_context(snap):
    occupants = {}
    for b in snap["bots"]:
        for mid in {b["map"], b["heading_to"]} - {None}:
            occupants.setdefault(mid, []).append(b["name"])
    maps = "\n".join(map_line(m, occupants) for m in snap["maps"] if m["mobs"])
    bots = "\n".join(bot_line(b) for b in snap["bots"])
    return f"Snapshot {snap['taken_at']}.\n\nMaps with mobs (id | name | mobs | bots):\n{maps}\n\nFleet:\n{bots}"


def chat(url, system, user, schema, name, max_tokens):
    body = {"model": "default", "temperature": 0.3, "max_tokens": max_tokens,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
            "response_format": {"type": "json_schema", "json_schema": {"name": name, "schema": schema}},
            "chat_template_kwargs": {"enable_thinking": False}}
    req = urllib.request.Request(url + "/v1/chat/completions", json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as r:
        return json.loads(json.load(r)["choices"][0]["message"]["content"])


def buyable(bot):
    """Item names the bot's options offer to buy, e.g. 'Zeco' from 'buy Zeco (225,000 mesos from Scott)'."""
    return {o["weapon"][4:].split(" (")[0] for o in bot.get("options", []) if o["weapon"].startswith("buy ")}


def check(plan, bot, snap):
    """The strategist's own work, checked against the facts: returns a problem or None."""
    by_name = {b["name"]: b for b in snap["bots"]}
    map_ids = {m["id"] for m in snap["maps"] if m["mobs"]}
    if plan["action"] == "move":
        if plan["target_map"] not in map_ids:
            return f"map {plan['target_map']} is not in the list"
        if plan["target_map"] in (bot["map"], bot["heading_to"]):
            return "move to the map it is already on or heading to"
    if plan["action"] == "party":
        p = by_name.get(plan["partner"] or "")
        if p is None or p["name"] == bot["name"]:
            return f"unknown partner {plan['partner']}"
        if abs(p["level"] - bot["level"]) > 5:
            return f"partner {p['name']} is {abs(p['level'] - bot['level'])} levels apart (EXP shares within 5)"
    if plan["action"] == "buy" and (plan.get("item") or "") not in buyable(bot):
        return f"{plan.get('item')} is not a weapon this bot can buy and wear"
    if bot["cap_reached"] and plan["action"] != "keep":
        return "bot is at its training cap, idle by design"
    return None


def plan_fleet(url, snap):
    context = fleet_context(snap)
    system = SYSTEM + "\n\n" + context  # shared by every request, so the server's prefix cache reuses it

    def one(b):
        detail = (f"Plan for {b['name']}: lv{b['level']} {b['job']} (planned {b['planned_job']}), EXP {b['exp_pct']}%, "
                  f"{b['meso']:,} mesos, gear: {', '.join(b['gear']) or 'none'}, top skills: "
                  f"{', '.join(b['top_skills'])}. Now: {b['advisor_status']}.\n"
                  f"Its facts and options:\n{bot_line(b)}\n"
                  "Answer keep, move (target_map from the list), party (partner by name) or buy (item from its options).")
        t0 = time.time()
        plan = chat(url, system, detail, PLAN_SCHEMA, "bot_plan", 200)
        plan["seconds"] = round(time.time() - t0, 1)
        plan["problem"] = check(plan, b, snap)
        return b["name"], plan

    with ThreadPoolExecutor(2) as ex:
        plans = dict(ex.map(one, snap["bots"]))
    notes = chat(url, system, "List up to 5 fleet level changes GrindAdvisor cannot make one bot at a time "
                 "(crowded maps, idle bots without reason, party pairs within 5 levels, gear or stats holding "
                 "bots back). Name the bots.", NOTES_SCHEMA, "fleet_notes", 600)["notes"]
    return plans, notes, []


FLEET_SCHEMA = {
    "type": "object",
    "properties": {
        "plans": {"type": "array", "items": {
            "type": "object",
            "properties": {"bot": {"type": "string"}, **PLAN_SCHEMA["properties"]},
            "required": ["bot"] + PLAN_SCHEMA["required"]}},
        "notes": {"type": "array", "maxItems": 5, "items": NOTE_ITEMS},
        "findings": {"type": "array", "maxItems": 5, "items": FINDING_ITEMS},
    },
    "required": ["plans", "notes", "findings"],
}

# Read only tools for the claude backend. Bash is allowed only for these command prefixes; anything else
# is refused in print mode. The MySQL user `strategist` has SELECT only.
READ_TOOLS = "Read,Grep,Glob,Bash"
ALLOWED = [
    "Read", "Grep", "Glob",
    "Bash(curl -s http://127.0.0.1:8089/api/botdebug:*)",
    "Bash(curl -s http://127.0.0.1:8089/api/mapinfo:*)",
    "Bash(docker exec cosmic-local-db-1 mysql -ustrategist -preadonly:*)",
]
TOOL_GUIDE = f"""Read only tools you may use to check a fact before relying on it:
- The snapshot file: {{snapshot}}
- WZ data under {ROOT / 'wz'} (Character.wz/Weapon/0<itemid>.img.xml has reqLevel, incPAD and stat
  requirements; Mob.wz/<id>.img.xml has level, maxHP, PDDamage and exp; String.wz has names).
- curl -s http://127.0.0.1:8089/api/botdebug?id=<bot id> and curl -s http://127.0.0.1:8089/api/mapinfo?id=<map id>
- docker exec cosmic-local-db-1 mysql -ustrategist -preadonly -N -e "<SELECT ...>" on the cosmic schema
  (characters, inventoryitems, inventoryequipment, shopitems, shops, queststatus).
Use them sparingly; the snapshot already carries the main facts."""


def plan_fleet_claude(snap, snapshot_path, model="sonnet", use_tools=True):
    """One `claude -p` call on the owner's Claude plan (no API key): the whole fleet in, every plan out."""
    prompt = (fleet_context(snap) + "\n\nWrite a plan for every bot in the fleet list (use its exact name), "
              "then up to 5 fleet notes and up to 5 findings. Every note and finding must be checkable "
              "against the snapshot or the read only tools.")
    system = SYSTEM
    cmd = ["claude", "-p", "--output-format", "json", "--json-schema", json.dumps(FLEET_SCHEMA),
           "--no-session-persistence", "--model", model]
    if use_tools:
        system += "\n\n" + TOOL_GUIDE.format(snapshot=Path(snapshot_path).resolve())
        cmd += ["--tools", READ_TOOLS, "--allowedTools", *ALLOWED,
                "--add-dir", str(ROOT / "wz"), "--add-dir", str(Path(snapshot_path).resolve().parent)]
    else:
        cmd += ["--tools", ""]
    cmd += ["--system-prompt", system]
    # Run outside the repo so the project's Claude Code settings and trust prompt don't apply.
    out = subprocess.run(cmd, input=prompt, capture_output=True, text=True, timeout=1800, cwd="/tmp")
    if out.returncode != 0:
        raise RuntimeError(f"claude -p failed: {out.stderr[-500:] or out.stdout[-500:]}")
    env = json.loads(out.stdout)
    if env.get("is_error"):
        raise RuntimeError(f"claude -p: {env.get('result')}")
    data = env.get("structured_output") or json.loads(env["result"])
    by_name = {b["name"]: b for b in snap["bots"]}
    plans = {}
    for p in data["plans"]:
        if p["bot"] in by_name:
            p["problem"] = check(p, by_name[p["bot"]], snap)
            plans[p.pop("bot")] = p
    for name in by_name.keys() - plans.keys():  # a bot the model skipped
        plans[name] = {"action": "keep", "target_map": None, "partner": None, "item": None,
                       "reason": "(no plan returned)", "confidence": "low", "problem": "model returned no plan"}
    usage = {k: env.get(k) for k in ("total_cost_usd", "duration_ms", "num_turns") if k in env}
    usage["models"] = list((env.get("modelUsage") or {}).keys())
    return plans, data["notes"], data.get("findings", []), usage


def report(snap, plans, notes, findings, label, secs):
    maps = {m["id"]: m["name"] for m in snap["maps"]}
    changes = [n for n, p in plans.items() if p["action"] != "keep"]
    flagged = [n for n, p in plans.items() if p["problem"]]
    lines = [f"# Fleet strategist report ({label})",
             f"Snapshot {snap['taken_at']}: {len(snap['bots'])} bots. Read only; nothing was sent to the game.",
             f"Proposed changes: {len(changes)} ({len(changes) - len(set(changes) & set(flagged))} pass the checks); "
             f"keep: {len(plans) - len(changes)}; plans failing a check: {len(flagged)}. {secs:.0f} s.", "",
             "## Findings (new problem classes, for the owner to approve as code)", ""]
    for f in findings:
        lines += [f"- **{f['pattern']}** ({', '.join(f['bots'])})", f"  - Evidence: {f['evidence']}",
                  f"  - Proposed check: {f['proposed_check']}"]
    if not findings:
        lines.append("- none")
    lines += ["", "## Fleet notes", ""]
    lines += [f"- {n['note']} ({', '.join(n['bots'])})" for n in notes] or ["- none"]
    lines += ["", "## Plan per bot", "",
              "| Bot | Lv | Job | Role | GrindAdvisor now | Strategist | Reason | Conf. | Check |",
              "|---|---|---|---|---|---|---|---|---|"]
    order = sorted(snap["bots"], key=lambda b: (plans[b["name"]]["action"] == "keep", b["name"]))
    for b in order:
        p = plans[b["name"]]
        target = {"keep": "keep",
                  "move": f"move to {maps.get(p['target_map'], p['target_map'])}",
                  "party": f"party with {p['partner']}",
                  "buy": f"buy {p.get('item')}"}[p["action"]]
        now = b["advisor_status"].replace("|", "/")[:90]
        lines.append(f"| {b['name']} | {b['level']} | {b['job']} | {b['role'][:28]} | {now} | {target} | "
                     f"{p['reason'].replace('|', '/')} | {p['confidence']} | {p['problem'] or 'ok'} |")
    return "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("snapshot")
    ap.add_argument("out")
    ap.add_argument("--url", default="http://127.0.0.1:31000")
    ap.add_argument("--model-label", default="")
    ap.add_argument("--backend", default="claude", choices=["openai", "claude"])
    ap.add_argument("--model", default="sonnet", help="claude backend: model alias, e.g. sonnet or opus")
    ap.add_argument("--no-tools", action="store_true", help="claude backend: snapshot only, no read tools")
    args = ap.parse_args()
    snap = json.loads(Path(args.snapshot).read_text())
    t0 = time.time()
    usage = {}
    if args.backend == "claude":
        plans, notes, findings, usage = plan_fleet_claude(snap, args.snapshot, args.model, not args.no_tools)
    else:
        plans, notes, findings = plan_fleet(args.url, snap)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "plans.json").write_text(json.dumps(
        {"plans": plans, "notes": notes, "findings": findings, "usage": usage}, indent=1))
    label = args.model_label or (", ".join(usage.get("models", [])) or args.model)
    (out / "report.md").write_text(report(snap, plans, notes, findings, label, time.time() - t0))
    print((out / "report.md").read_text()[:2500])


if __name__ == "__main__":
    main()
