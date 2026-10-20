#!/usr/bin/env python3
"""Coordination for agents sharing the fleet: claims on work, a lock for actions only one agent may take at once, and a journal.

Several agent sessions work the same server, branch and Mac. Two of them fixed the same Buddha freeze on Oct 20,
and one restarted the server while the other watched a bot. Code, tests and analysis can run in parallel; deploys,
live bot commands, parks, settings and database writes go one at a time. This keeps both kinds visible.

State lives in COSMIC_AGENTS_DIR (default ~/Library/Application Support/cosmic-agents) on the Mac, where every
session reaches it through the device bridge:
    claims/<id>.json   one per claim: session, kind (work or watch), subjects, intent, expiry
    locks/<name>/      a held lock (mkdir is atomic); owner.json inside
    journal.md         one line per start, finish or deploy; read the tail before doing anything

Subjects are tokens such as bot:255, map:800020130, issue:188, area:combat, file:BotCombatManager. Two claims
conflict when they share a token. A watch claim (kind watch) holds bots still: deploy.sh refuses while one is live.

    agents.py journal [-n 20]                         the latest lines
    agents.py note --session S "text"                 add a line
    agents.py check bot:255 issue:188                 live claims on any of these; exit 1 when there are some
    agents.py claim --session S [--watch] [--hours 2] [--issue N] --intent "..." subject...
    agents.py refresh ID [--hours 2]                  push a claim's expiry out
    agents.py release ID [--note "..."]               end a claim (and unlabel its issue)
    agents.py list                                    every live claim
    agents.py lock NAME --session S [--minutes 30]    take a lock; exit 1 when someone else holds it
    agents.py unlock NAME --session S
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import uuid
from pathlib import Path

REPO = "jlee5814/cosmic-navi"
CLAIMED_LABEL = "claimed"


def home():
    d = Path(os.environ.get("COSMIC_AGENTS_DIR", Path.home() / "Library/Application Support/cosmic-agents"))
    for sub in ("claims", "locks"):
        (d / sub).mkdir(parents=True, exist_ok=True)
    return d


def stamp(t=None):
    return time.strftime("%Y-%m-%d %H:%M", time.localtime(t or time.time()))


def note(text, session):
    with open(home() / "journal.md", "a") as f:
        f.write(f"- {stamp()} {session}: {text}\n")


def live_claims(now=None):
    now = now or time.time()
    out = []
    for p in sorted((home() / "claims").glob("*.json")):
        try:
            c = json.loads(p.read_text())
        except (OSError, json.JSONDecodeError):
            continue
        if c["expires"] > now:
            out.append(c)
    return out


def conflicts(subjects, session=None, now=None):
    """Live claims by other sessions that share a subject token with these."""
    want = set(subjects)
    return [c for c in live_claims(now) if c["session"] != session and want & set(c["subjects"])]


def gh(*args):
    """Best effort GitHub REST call through gh; claims work without it."""
    try:
        subprocess.run(["gh", "api", *args], check=True, capture_output=True, timeout=30)
        return True
    except (OSError, subprocess.SubprocessError):
        return False


def claim(session, subjects, intent, hours=2.0, watch=False, issue=None, force=False):
    if issue:
        subjects = list(subjects) + [f"issue:{issue}"]
    clash = conflicts(subjects, session)
    if clash and not force:
        return None, clash
    cid = f"{time.strftime('%m%d%H%M')}-{uuid.uuid4().hex[:6]}"
    c = {"id": cid, "session": session, "kind": "watch" if watch else "work", "subjects": sorted(set(subjects)),
         "intent": intent, "issue": issue, "started": stamp(), "expires": time.time() + hours * 3600}
    path = home() / "claims" / f"{cid}.json"
    with open(path, "x") as f:  # exclusive create: two sessions never share an id
        json.dump(c, f, indent=1)
    note(f"claimed {cid} ({c['kind']}) {' '.join(c['subjects'])}: {intent}", session)
    if issue:
        gh(f"repos/{REPO}/issues/{issue}/labels", "-X", "POST", "-f", f"labels[]={CLAIMED_LABEL}")
        gh(f"repos/{REPO}/issues/{issue}/comments", "-f", f"body=Claimed by {session}: {intent}")
    return c, clash


def load(cid):
    return json.loads((home() / "claims" / f"{cid}.json").read_text())


def refresh(cid, hours=2.0):
    c = load(cid)
    c["expires"] = time.time() + hours * 3600
    (home() / "claims" / f"{cid}.json").write_text(json.dumps(c, indent=1))
    return c


def release(cid, text=None):
    c = load(cid)
    (home() / "claims" / f"{cid}.json").unlink()
    note(f"released {cid}" + (f": {text}" if text else ""), c["session"])
    if c.get("issue"):
        gh(f"repos/{REPO}/issues/{c['issue']}/labels/{CLAIMED_LABEL}", "-X", "DELETE")
    return c


def lock(name, session, minutes=30.0, now=None):
    """Take the named lock, or return its holder. A lock past its expiry is stale and is taken over."""
    now = now or time.time()
    d = home() / "locks" / name
    try:
        d.mkdir()
    except FileExistsError:
        try:
            owner = json.loads((d / "owner.json").read_text())
        except (OSError, json.JSONDecodeError):
            owner = {"session": "?", "expires": 0}
        if owner["session"] != session and owner["expires"] > now:
            return owner
        shutil.rmtree(d, ignore_errors=True)
        d.mkdir()
    (d / "owner.json").write_text(json.dumps({"session": session, "since": stamp(now), "expires": now + minutes * 60}))
    return None


def unlock(name, session):
    d = home() / "locks" / name
    try:
        owner = json.loads((d / "owner.json").read_text())
    except (OSError, json.JSONDecodeError):
        owner = {"session": session}
    if owner["session"] == session:
        shutil.rmtree(d, ignore_errors=True)
        return True
    return False


def describe(c):
    left = max(0, int((c["expires"] - time.time()) / 60))
    return f"{c['id']} {c['kind']} by {c['session']} ({left} min left): {' '.join(c['subjects'])}: {c['intent']}"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("journal"); p.add_argument("-n", type=int, default=20)
    p = sub.add_parser("note"); p.add_argument("--session", required=True); p.add_argument("text")
    p = sub.add_parser("check"); p.add_argument("subjects", nargs="+"); p.add_argument("--session")
    p = sub.add_parser("claim")
    p.add_argument("--session", required=True); p.add_argument("--intent", required=True)
    p.add_argument("--hours", type=float, default=2.0); p.add_argument("--watch", action="store_true")
    p.add_argument("--issue", type=int); p.add_argument("--force", action="store_true"); p.add_argument("subjects", nargs="*")
    p = sub.add_parser("refresh"); p.add_argument("id"); p.add_argument("--hours", type=float, default=2.0)
    p = sub.add_parser("release"); p.add_argument("id"); p.add_argument("--note")
    sub.add_parser("list")
    p = sub.add_parser("lock"); p.add_argument("name"); p.add_argument("--session", required=True)
    p.add_argument("--minutes", type=float, default=30.0)
    p = sub.add_parser("unlock"); p.add_argument("name"); p.add_argument("--session", required=True)
    a = ap.parse_args(argv)

    if a.cmd == "journal":
        path = home() / "journal.md"
        lines = path.read_text().splitlines() if path.exists() else []
        print("\n".join(lines[-a.n:]) or "(empty)")
    elif a.cmd == "note":
        note(a.text, a.session)
    elif a.cmd == "check":
        clash = conflicts(a.subjects, a.session)
        for c in clash:
            print(describe(c))
        return 1 if clash else 0
    elif a.cmd == "claim":
        if not a.subjects and not a.issue:
            ap.error("claim needs a subject or --issue")
        c, clash = claim(a.session, a.subjects, a.intent, a.hours, a.watch, a.issue, a.force)
        for x in clash:
            print(("overridden: " if c else "conflict: ") + describe(x))
        if c is None:
            return 1
        print(c["id"])
    elif a.cmd == "refresh":
        print(describe(refresh(a.id, a.hours)))
    elif a.cmd == "release":
        release(a.id, a.note)
    elif a.cmd == "list":
        for c in live_claims():
            print(describe(c))
    elif a.cmd == "lock":
        holder = lock(a.name, a.session, a.minutes)
        if holder:
            print(f"held by {holder['session']} since {holder.get('since', '?')}")
            return 1
    elif a.cmd == "unlock":
        if not unlock(a.name, a.session):
            print("not yours")
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
