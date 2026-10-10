#!/usr/bin/env python3
"""Navi responder: a model looks at stalls the game's rules can't settle and picks one safe action.

Fleet ops (BotFleetOps in the game server) already rescues a frozen bot by itself. What a scroll can't
fix comes here: an errand loop, a bot earning nothing on an errand, a spot that refreezes a bot after
two auto rescues. Every run (a LaunchAgent, every 5 minutes):

1. GET /api/fleetops/incidents. Nothing open: exit without calling a model.
2. For each new incident (a bot not handled for the same kind in the last hour, at most 3 a run and
   MAX_CALLS_PER_DAY a day), add the bot's recent server log lines and chat.
3. One `claude -p` call on the owner's plan, no tools, a JSON schema reply: an action from a fixed
   menu (rescue, hold_errands, sell_trash, park, none), a reason, and an optional GitHub issue.
4. POST the action to /api/fleetops/act, which runs it on the bot's next tick through the same code
   and guards as navi rescue and park, and whispers the owner. The server caps each bot at 3 actions
   in 2 hours whatever the model says.
5. File the issue (label responder) when the model says the cause is a code bug; one per title.

Usage: responder.py [--dry-run] [--model sonnet] [--incidents file.json]
Log: runs/log.jsonl next to this file (the LaunchAgent copy keeps its own).
"""
import argparse
import datetime
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

API = "http://127.0.0.1:8089"
REPO = "jlee5814/cosmic-navi"
CONTAINER = "cosmic-local-maplestory-1"
DOCKER = os.environ.get("DOCKER", "/usr/local/bin/docker")
GH = os.environ.get("GH", "/opt/homebrew/bin/gh")
CLAUDE = os.environ.get("CLAUDE", str(Path.home() / ".local/bin/claude"))
HERE = Path(__file__).resolve().parent
RUNS = HERE / "runs"
MAX_PER_RUN = 3
MAX_CALLS_PER_DAY = 24
REPEAT_AFTER_S = 60 * 60

ACTIONS = ["rescue", "hold_errands", "sell_trash", "park", "none"]
SCHEMA = {
    "type": "object",
    "properties": {
        "diagnosis": {"type": "string", "description": "What is going wrong and why, from the evidence."},
        "action": {"type": "string", "enum": ACTIONS},
        "minutes": {"type": "integer", "minimum": 15, "maximum": 60,
                    "description": "hold_errands only: how long to hold optional errands."},
        "reason": {"type": "string", "description": "One plain sentence for the owner, under 120 characters, no quotes."},
        "file_issue": {"type": "boolean", "description": "True when the cause looks like a code bug to fix."},
        "issue_title": {"type": "string"},
        "issue_body": {"type": "string"},
    },
    "required": ["diagnosis", "action", "reason", "file_issue"],
}

SYSTEM = """You are the incident responder for a fleet of MapleStory bots on a private server. Each bot runs
deterministic Java rules: it grinds a map, runs errands to shops (pots, ammo, a full bag, a weapon
upgrade) and travels by portal, ship and return scroll. A watcher already scrolls a frozen bot back to
town by itself. You see only what that can't settle:

- errand_loop: a third errand to the same map within 45 minutes.
- no_exp_on_errand: busy on an errand for 30 minutes or more without gaining EXP.
- refreezing: frozen again after two auto rescues in two hours, so the spot itself traps it.
- no_exp_after_rescue: still earning nothing after an auto rescue.
- low_exp_on_errand: on an errand for 30 minutes while earning under a fifth of its usual EXP per hour
  (expPerHourLast30Min against usualExpPerHour).
- low_exp_after_rescue: still under a fifth of its usual pace after an auto rescue, often a spot or a map
  that keeps it from fighting.

Pick exactly one action. Each runs through the game's own code and safety checks:
- rescue: town return scroll, then back to its own plan. Useless for an errand loop (the loop restarts).
- hold_errands: drop the current errand and allow no errands for `minutes` (15 to 60), so the bot goes
  back to grinding. Right for a loop on an optional trip (a weapon upgrade, a full bag that can't be
  emptied). Risky if it is out of pots or ammo, since it can't restock during the hold.
- sell_trash: a sell visit at a shop on its current map. Only useful if it stands on a shop map and the
  bag holds sellable junk.
- park: log the bot out and keep it out (roster bots only). For a bot that can't do anything useful,
  such as one trapped by a spot after rescues failed. It earns nothing parked, so prefer the others.
- none: when nothing on the menu helps, or the evidence is unclear.

Prefer the least drastic action that addresses the cause. Read the evidence: the bag (USE stacks with
the rule that classifies each), errand maps, status line, and log lines (bot-sell, bot-errand, travel
give ups). When the cause is a bug in the rules (an item the sell rules can't shed, a route that can't
be traveled, a spot that traps bots), set file_issue with a short title naming the bot, map and
symptom, and a body with the evidence and the likely code area. The action buys time; the issue gets it
fixed. Write the reason as one plain sentence for the owner, without quotation marks."""


def get(path):
    with urllib.request.urlopen(API + path, timeout=20) as r:
        return r.read().decode()


def post(path, body):
    req = urllib.request.Request(API + path, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.read().decode().strip()
    except urllib.error.HTTPError as e:
        return e.read().decode().strip()


def log_lines(name, minutes=40, limit=40):
    """Recent server log lines for this bot, minus the nav graph noise."""
    try:
        out = subprocess.run([DOCKER, "logs", "--since", f"{minutes}m", CONTAINER], capture_output=True,
                             text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired):
        return []
    pat = re.compile(re.escape(name) + r"(?!\d)")
    keep = [line[:240] for line in (out.stdout + out.stderr).splitlines()
            if pat.search(line) and "nav graph" not in line]
    return keep[-limit:]


def chat_lines(name, map_id):
    try:
        info = json.loads(get(f"/api/mapinfo?id={map_id}"))
    except Exception:
        return []
    return [f"{c.get('t')} {c.get('m')}" for c in info.get("chat") or [] if c.get("n") == name][-15:]


def prompt_for(incident):
    facts = {k: v for k, v in incident.items() if not k.startswith("_")}
    return ("Incident (JSON from the game server):\n" + json.dumps(facts, indent=1)
            + "\n\nRecent server log lines for this bot:\n" + "\n".join(incident.get("_log") or ["(none)"])
            + "\n\nWhat it said in map chat recently:\n" + "\n".join(incident.get("_chat") or ["(none)"])
            + "\n\nDiagnose it and pick one action.")


def ask_model(incident, model):
    cmd = [CLAUDE, "-p", "--output-format", "json", "--json-schema", json.dumps(SCHEMA),
           "--no-session-persistence", "--model", model, "--tools", "", "--system-prompt", SYSTEM]
    t0 = time.time()
    # Run outside the repo so the project's Claude Code settings and trust prompt don't apply.
    out = subprocess.run(cmd, input=prompt_for(incident), capture_output=True, text=True, timeout=600, cwd="/tmp")
    if out.returncode != 0:
        raise RuntimeError(f"claude -p failed: {out.stderr[-400:] or out.stdout[-400:]}")
    env = json.loads(out.stdout)
    if env.get("is_error"):
        raise RuntimeError(f"claude -p: {env.get('result')}")
    data = env.get("structured_output") or json.loads(env["result"])
    return data, round(time.time() - t0, 1)


def clean_reason(s):
    """The server's flat JSON reader stops at a quote; keep the owner's whisper to one clean line."""
    return re.sub(r"[\"\\\n\r]", "", s or "").strip()[:120]


def file_issue(decision, incident, dry_run):
    title = (decision.get("issue_title") or "").strip()[:120]
    if not title:
        return None
    if dry_run:
        return "dry run: " + title
    found = subprocess.run([GH, "issue", "list", "--repo", REPO, "--state", "open", "--label", "responder",
                            "--search", title, "--json", "title,url"], capture_output=True, text=True)
    for issue in json.loads(found.stdout or "[]"):
        if issue["title"] == title:
            return issue["url"]
    facts = {k: v for k, v in incident.items() if not k.startswith("_")}
    body = ((decision.get("issue_body") or decision.get("diagnosis") or "")
            + "\n\nIncident from the navi responder:\n\n```json\n" + json.dumps(facts, indent=1) + "\n```\n")
    made = subprocess.run([GH, "issue", "create", "--repo", REPO, "--title", title, "--label", "responder",
                           "--body", body], capture_output=True, text=True)
    return made.stdout.strip() or made.stderr.strip()[:200]


def due(incidents, state, now, budget):
    """Incidents worth a model call this run: new, or a different kind, or an hour since the last look."""
    out = []
    for inc in incidents:
        seen = state.get(str(inc["id"]))
        if seen and seen.get("kind") == inc["kind"] and now - seen.get("at", 0) < REPEAT_AFTER_S:
            continue
        if len(out) >= budget:
            break
        out.append(inc)
    return out


def load_state():
    try:
        return json.loads((RUNS / "state.json").read_text())
    except (OSError, ValueError):
        return {}


def calls_today():
    day = datetime.date.today().isoformat()
    try:
        return sum(1 for line in (RUNS / "log.jsonl").read_text().splitlines()
                   if json.loads(line).get("ts", "").startswith(day))
    except (OSError, ValueError):
        return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="ask the model, but neither act nor file issues")
    ap.add_argument("--model", default="sonnet")
    ap.add_argument("--incidents", help="read incidents (and their _log, _chat evidence) from this file")
    ap.add_argument("--runs", help="where the log and state go (default runs/ next to this file)")
    args = ap.parse_args()
    global RUNS
    if args.runs:
        RUNS = Path(args.runs)
    RUNS.mkdir(parents=True, exist_ok=True)

    incidents = json.loads(Path(args.incidents).read_text() if args.incidents else get("/api/fleetops/incidents"))
    if not incidents:
        return 0
    state = load_state()
    now = time.time()
    for inc in due(incidents, state, now, min(MAX_PER_RUN, MAX_CALLS_PER_DAY - calls_today())):
        if "_log" not in inc:  # a replay file brings its own evidence
            inc["_log"] = log_lines(inc["name"])
            inc["_chat"] = chat_lines(inc["name"], inc["map"])
        record = {"ts": datetime.datetime.now().isoformat(timespec="seconds"), "bot": inc["name"],
                  "kind": inc["kind"], "dry_run": args.dry_run}
        try:
            decision, secs = ask_model(inc, args.model)
            record.update(decision=decision, model_seconds=secs)
            action = decision.get("action", "none")
            if action in ACTIONS and action != "none" and not args.dry_run:
                record["result"] = post("/api/fleetops/act", {"bot": inc["id"], "action": action,
                                                              "arg": int(decision.get("minutes") or 30),
                                                              "reason": clean_reason(decision.get("reason"))})
            if decision.get("file_issue"):
                record["issue"] = file_issue(decision, inc, args.dry_run)
        except Exception as e:  # one bad incident must not stop the rest
            record["error"] = str(e)[:400]
        state[str(inc["id"])] = {"kind": inc["kind"], "at": now}
        record["incident"] = {k: v for k, v in inc.items() if not k.startswith("_")}
        with open(RUNS / "log.jsonl", "a") as f:
            f.write(json.dumps(record) + "\n")
        print(f'{inc["name"]} {inc["kind"]}: {record.get("decision", {}).get("action")} '
              f'{record.get("result", "")} {record.get("issue") or ""} {record.get("error", "")}'.strip())
    (RUNS / "state.json").write_text(json.dumps(state))
    return 0


if __name__ == "__main__":
    sys.exit(main())
