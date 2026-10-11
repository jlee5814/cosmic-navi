"""Turn stuck spots that keep coming back into fix tasks.

Each snapshot's falling and frozen bots go into a history file. A spot is one map, one flag and one
place on the map (within SPOT_RADIUS_PX; a falling column matches on x alone). A spot recurs once two
bots were stuck there, or one bot was stuck there in snapshots at least RECUR_GAP_MIN apart. A
recurring spot opens a GitHub issue labeled stuck-spot with every sighting, a path trace of each bot
still stuck now, and a simulator command to replay it. Later sightings comment on the open issue. A
spot whose issue was closed and then recurs opens a new issue that links the old one, since the fix
did not hold.

Usage: stuck_tracker.py <snapshot.json> [--dry-run]
Prints one line per spot it acted on, for the nightly report.
"""
import json
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

REPO = "jlee5814/cosmic-navi"
LABEL = "stuck-spot"
DASH = "http://127.0.0.1:8089"
HERE = Path(__file__).resolve().parent
HISTORY = HERE / "runs" / "nightly" / "stuck-history.jsonl"
TRACES = HERE / "runs" / "nightly" / "traces"
STUCK_FLAGS = ("frozen", "falling")
SPOT_RADIUS_PX = 150
RECUR_GAP_MIN = 30
TRACE_WAIT_S = 7  # the path recorder keeps the last 120 ticks, about 6 s
MARKER = re.compile(r"<!-- stuck-spot map=(\d+) flag=(\w+) x=(-?\d+) y=(-?\d+) -->")


def sightings_from(snapshot):
    """The stuck bots in one snapshot, as history records."""
    names = {m["id"]: m["name"] for m in snapshot.get("maps", [])}
    out = []
    for b in snapshot["bots"]:
        pos = (b.get("motion") or {}).get("pos")
        for flag in STUCK_FLAGS:
            if flag in b.get("flags", []) and pos:
                out.append({"taken_at": snapshot["taken_at"], "bot": b["name"], "bot_id": b["id"],
                            "map": b["map"], "map_name": names.get(b["map"], str(b["map"])), "flag": flag,
                            "x": pos[0], "y": pos[1], "heading_to": b.get("heading_to"),
                            "status": b.get("advisor_status", "")})
    return out


def same_spot(a, b):
    if a["map"] != b["map"] or a["flag"] != b["flag"] or abs(a["x"] - b["x"]) > SPOT_RADIUS_PX:
        return False
    return a["flag"] == "falling" or abs(a["y"] - b["y"]) <= SPOT_RADIUS_PX


def spots(history):
    """Group sightings into spots; each spot's anchor is its first sighting."""
    groups = []
    for s in history:
        for g in groups:
            if same_spot(g[0], s):
                g.append(s)
                break
        else:
            groups.append([s])
    return groups


def _minutes(taken_at):
    return time.mktime(time.strptime(taken_at, "%Y-%m-%d %H:%M")) / 60


def recurs(group):
    if len({s["bot"] for s in group}) >= 2:
        return True
    times = sorted({_minutes(s["taken_at"]) for s in group})
    return len(times) >= 2 and times[-1] - times[0] >= RECUR_GAP_MIN


def matching_issue(anchor, issues):
    """The newest issue for this spot (open preferred), from the marker in its body."""
    best = None
    for issue in issues:
        m = MARKER.search(issue.get("body") or "")
        if not m:
            continue
        spot = {"map": int(m[1]), "flag": m[2], "x": int(m[3]), "y": int(m[4])}
        if same_spot(spot, anchor) and (best is None or issue["state"] == "OPEN" and best["state"] != "OPEN"
                                        or issue["state"] == best["state"] and issue["number"] > best["number"]):
            best = issue
    return best


def capture_trace(bot_id, name):
    """Two calls to /api/bot/pathlog: the first starts the recorder, the second dumps about 6 s of ticks."""
    try:
        urllib.request.urlopen(f"{DASH}/api/bot/pathlog?id={bot_id}", timeout=10).read()
        time.sleep(TRACE_WAIT_S)
        report = json.load(urllib.request.urlopen(f"{DASH}/api/bot/pathlog?id={bot_id}", timeout=10)).get("report", "")
    except Exception as e:  # the trace is extra evidence; the issue goes out without it
        return f"(no trace: {e})"
    TRACES.mkdir(parents=True, exist_ok=True)
    (TRACES / f"{name}-{time.strftime('%Y%m%d-%H%M')}.txt").write_text(report)
    return report


def sighting_rows(group):
    rows = ["| Seen | Bot | Flag | x | y | Heading to | Status |", "|---|---|---|---|---|---|---|"]
    for s in group:
        status = s["status"].replace("|", "/")[:90]
        rows.append(f"| {s['taken_at']} | {s['bot']} | {s['flag']} | {s['x']} | {s['y']} | {s['heading_to'] or ''} | {status} |")
    return "\n".join(rows)


def trace_blocks(traces):
    blocks = []
    for name, report in traces.items():
        lines = report.splitlines()
        shown = "\n".join(lines[:80]) + (f"\n... {len(lines) - 80} more lines" if len(lines) > 80 else "")
        blocks.append(f"<details><summary>Path trace: {name}</summary>\n\n```\n{shown}\n```\n</details>")
    return "\n\n".join(blocks)


def issue_body(anchor, group, traces, reopened_from=None):
    x, y = anchor["x"], anchor["y"]
    where = f"near x {x}" if anchor["flag"] == "falling" else f"near x {x}, y {y}"
    what = ("Bots fall here and never land." if anchor["flag"] == "falling"
            else "Bots stop here while they mean to grind or travel: no movement and no EXP for a full minute.")
    lines = [
        f"<!-- stuck-spot map={anchor['map']} flag={anchor['flag']} x={x} y={y} -->",
        f"{what} The fleet snapshot flagged {anchor['map_name']} ({anchor['map']}) {where} "
        f"{len(group)} times, for {len({s['bot'] for s in group})} bot(s).",
    ]
    if reopened_from:
        lines.append(f"\nIssue #{reopened_from} fixed this spot once; it came back, so that fix did not hold.")
    lines += ["", "## Sightings", "", sighting_rows(group), "", "## Replay", "",
              "Spawn a bot on the spot and give it the move it was attempting (toward the exit for the map it was heading to):",
              "", "```", f"tmp/navdiag/sim.sh {anchor['map']} --bot b:{x},{y} --move b:<target x>,<target y> --ticks 1200 --trace b", "```"]
    if traces:
        lines += ["", "## Path traces (bots still stuck when this was filed)", "", trace_blocks(traces)]
    lines += ["", "## Done when", "",
              "- The replay reaches its target from this spot.",
              "- No snapshot flags this spot for a week of nightly runs."]
    return "\n".join(lines)


def gh(*args):
    return subprocess.run(["gh", *args, "--repo", REPO], capture_output=True, text=True, check=True).stdout


def main():
    snap_path, dry = sys.argv[1], "--dry-run" in sys.argv
    snapshot = json.loads(Path(snap_path).read_text())
    new = sightings_from(snapshot)
    history = [json.loads(l) for l in HISTORY.read_text().splitlines() if l.strip()] if HISTORY.exists() else []
    seen = {(s["taken_at"], s["bot"], s["flag"]) for s in history}
    new = [s for s in new if (s["taken_at"], s["bot"], s["flag"]) not in seen]
    if not dry and new:
        HISTORY.parent.mkdir(parents=True, exist_ok=True)
        with HISTORY.open("a") as f:
            for s in new:
                f.write(json.dumps(s) + "\n")
    history += new
    new_keys = {(s["taken_at"], s["bot"], s["flag"]) for s in new}

    issues = []
    if not dry:
        subprocess.run(["gh", "label", "create", LABEL, "--repo", REPO, "--color", "B60205",
                        "--description", "A map spot where bots keep getting stuck"], capture_output=True, text=True)
        issues = json.loads(gh("issue", "list", "--label", LABEL, "--state", "all", "--limit", "200",
                               "--json", "number,state,body,title"))
    acted = 0
    for group in spots(history):
        fresh = [s for s in group if (s["taken_at"], s["bot"], s["flag"]) in new_keys]
        if not fresh or not recurs(group):
            continue  # nothing new here, or seen only once so far
        acted += 1
        anchor = group[0]
        title = (f"Stuck spot: {anchor['map_name']} ({anchor['map']}) near x {anchor['x']}"
                 + ("" if anchor["flag"] == "falling" else f", y {anchor['y']}") + f", {anchor['flag']}")
        issue = matching_issue(anchor, issues)
        traces = {} if dry else {s["bot"]: capture_trace(s["bot_id"], s["bot"]) for s in fresh}
        if issue and issue["state"] == "OPEN":
            comment = "Seen again.\n\n" + sighting_rows(fresh) + ("\n\n" + trace_blocks(traces) if traces else "")
            if not dry:
                gh("issue", "comment", str(issue["number"]), "--body", comment)
            print(f"- {title}: seen again, commented on #{issue['number']}")
            continue
        body = issue_body(anchor, group, traces, reopened_from=issue["number"] if issue else None)
        if dry:
            print(f"- {title}: would open an issue\n\n{body}\n")
            continue
        url = gh("issue", "create", "--title", title, "--label", LABEL, "--label", "bug", "--body", body).strip()
        issues.append({"number": int(url.rsplit("/", 1)[-1]), "state": "OPEN", "body": body, "title": title})
        print(f"- {title}: opened {url}")
    if not acted:
        print(f"- No stuck spot recurred. {len(new)} new stuck sighting(s) logged, "
              f"{len(spots(history))} spot(s) on file.")


if __name__ == "__main__":
    main()
