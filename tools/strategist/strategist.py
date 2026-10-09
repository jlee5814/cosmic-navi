#!/usr/bin/env python3
"""Fleet strategist, read-only: asks a model for a plan per bot and fleet notes, then writes a report
comparing them with what GrindAdvisor and the roster chose. Nothing is sent to the game.

Usage: strategist.py <snapshot.json> <out dir> [--backend openai|claude] [--url ...] [--model-label 8B]
  openai  any OpenAI compatible server (SGLang locally): one request per bot plus one for notes
  claude  the Claude Code CLI (`claude -p`) on the owner's Claude plan: one call for the whole fleet
"""
import argparse
import json
import subprocess
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

SYSTEM = """You plan for a fleet of MapleStory (v83) bots owned by one player. Each bot already runs on its own:
GrindAdvisor picks where it grinds, when it shops and what gear it chases, one bot at a time. You see the
whole fleet, so look for what a single bot cannot see: a better map from the list, a party partner,
crowding, a bot sitting idle without reason.

Facts you must respect:
- Roles and training caps were set by the owner on purpose. Never suggest leveling past a training cap;
  a bot with its cap reached is idle by design, so keep it.
- Party EXP is shared only among members within 5 levels of each other.
- Only suggest maps from the map list, by id.
- A bot shopping, resupplying or travelling is mid task; keep it unless something is clearly wrong.
- When the current choice is reasonable, answer keep. Changing a working plan has a cost.

Be concrete and brief. Reasons are one short sentence."""

PLAN_SCHEMA = {
    "type": "object",
    "properties": {
        "action": {"type": "string", "enum": ["keep", "move", "party"]},
        "target_map": {"type": ["integer", "null"]},
        "partner": {"type": ["string", "null"]},
        "reason": {"type": "string", "maxLength": 200},
        "confidence": {"type": "string", "enum": ["low", "medium", "high"]},
    },
    "required": ["action", "target_map", "partner", "reason", "confidence"],
}
NOTES_SCHEMA = {
    "type": "object",
    "properties": {"notes": {"type": "array", "maxItems": 5, "items": {
        "type": "object",
        "properties": {"note": {"type": "string", "maxLength": 240},
                       "bots": {"type": "array", "items": {"type": "string"}}},
        "required": ["note", "bots"]}}},
    "required": ["notes"],
}


def map_line(m, occupants):
    mobs = ", ".join(f"{x['name']} lv{x['level']} x{x['spawns']}" for x in m["mobs"]) or "no mobs"
    where = f"{m['name']} ({m['street']})" if m["street"] and m["street"] != m["name"] else m["name"]
    who = ", ".join(occupants.get(m["id"], [])) or "none"
    return f"{m['id']} | {where} | {mobs} | bots here or heading here: {who}"


def bot_line(b):
    cap = f"cap {b['training_cap']}{' reached' if b['cap_reached'] else ''}" if b["training_cap"] else "no cap"
    return (f"{b['name']} | lv{b['level']} {b['job']} | role: {b['role']} | {cap} | map {b['map']}"
            f"{' -> ' + str(b['heading_to']) if b['heading_to'] else ''} | party {b['party'] or 'none'} | "
            f"now: {b['advisor_status']}")


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
                  "Answer keep, move (target_map from the list) or party (partner by name).")
        t0 = time.time()
        plan = chat(url, system, detail, PLAN_SCHEMA, "bot_plan", 160)
        plan["seconds"] = round(time.time() - t0, 1)
        plan["problem"] = check(plan, b, snap)
        return b["name"], plan

    with ThreadPoolExecutor(2) as ex:
        plans = dict(ex.map(one, snap["bots"]))
    notes = chat(url, system, "List up to 5 fleet level changes GrindAdvisor cannot make one bot at a time "
                 "(crowded maps, idle bots without reason, party pairs within 5 levels). Name the bots.",
                 NOTES_SCHEMA, "fleet_notes", 600)["notes"]
    return plans, notes


FLEET_SCHEMA = {
    "type": "object",
    "properties": {
        "plans": {"type": "array", "items": {
            "type": "object",
            "properties": {"bot": {"type": "string"}, **PLAN_SCHEMA["properties"]},
            "required": ["bot"] + PLAN_SCHEMA["required"]}},
        "notes": NOTES_SCHEMA["properties"]["notes"],
    },
    "required": ["plans", "notes"],
}


def plan_fleet_claude(snap, model=None):
    """One `claude -p` call on the owner's Claude plan (no API key): the whole fleet in, every plan out."""
    prompt = (fleet_context(snap) + "\n\nWrite a plan for every bot in the fleet list (use its exact name), "
              "then up to 5 fleet notes. Every note must be checkable against the snapshot above.")
    cmd = ["claude", "-p", "--output-format", "json", "--json-schema", json.dumps(FLEET_SCHEMA),
           "--system-prompt", SYSTEM, "--tools", "", "--no-session-persistence"]
    if model:
        cmd += ["--model", model]
    # Run outside the repo so the project's Claude Code settings and trust prompt don't apply.
    out = subprocess.run(cmd, input=prompt, capture_output=True, text=True, timeout=900, cwd="/tmp")
    if out.returncode != 0:
        raise RuntimeError(f"claude -p failed: {out.stderr[-500:]}")
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
        plans[name] = {"action": "keep", "target_map": None, "partner": None, "reason": "(no plan returned)",
                       "confidence": "low", "problem": "model returned no plan"}
    usage = {k: env.get(k) for k in ("total_cost_usd", "duration_ms", "num_turns") if k in env}
    return plans, data["notes"], usage


def report(snap, plans, notes, label, secs):
    maps = {m["id"]: m["name"] for m in snap["maps"]}
    changes = [n for n, p in plans.items() if p["action"] != "keep"]
    flagged = [n for n, p in plans.items() if p["problem"]]
    lines = [f"# Fleet strategist report ({label})",
             f"Snapshot {snap['taken_at']}: {len(snap['bots'])} bots. Read only; nothing was sent to the game.",
             f"Proposed changes: {len(changes)} ({len(changes) - len(set(changes) & set(flagged))} pass the checks); "
             f"keep: {len(plans) - len(changes)}; plans failing a check: {len(flagged)}. {secs:.0f} s.", "",
             "## Fleet notes", ""]
    lines += [f"- {n['note']} ({', '.join(n['bots'])})" for n in notes] or ["- none"]
    lines += ["", "## Plan per bot", "",
              "| Bot | Lv | Job | Role | GrindAdvisor now | Strategist | Reason | Conf. | Check |",
              "|---|---|---|---|---|---|---|---|---|"]
    order = sorted(snap["bots"], key=lambda b: (plans[b["name"]]["action"] == "keep", b["name"]))
    for b in order:
        p = plans[b["name"]]
        target = {"keep": "keep",
                  "move": f"move to {maps.get(p['target_map'], p['target_map'])}",
                  "party": f"party with {p['partner']}"}[p["action"]]
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
    ap.add_argument("--backend", default="openai", choices=["openai", "claude"])
    ap.add_argument("--model", default=None, help="claude backend: model alias, e.g. sonnet or opus")
    args = ap.parse_args()
    snap = json.loads(Path(args.snapshot).read_text())
    t0 = time.time()
    usage = {}
    if args.backend == "claude":
        plans, notes, usage = plan_fleet_claude(snap, args.model)
    else:
        plans, notes = plan_fleet(args.url, snap)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "plans.json").write_text(json.dumps({"plans": plans, "notes": notes, "usage": usage}, indent=1))
    (out / "report.md").write_text(report(snap, plans, notes, args.model_label, time.time() - t0))
    print((out / "report.md").read_text()[:1500])


if __name__ == "__main__":
    main()
