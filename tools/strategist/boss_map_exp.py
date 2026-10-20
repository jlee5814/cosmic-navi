"""EXP a bot earns on a grind map holding a boss it can't kill alone, against what it earns elsewhere and what
the grind planner promised (docs/bot/kb/kb_bot_area_boss_maps.md).

The planner (BotGrindAdvisor.profileFor) leaves bosses out of a map's rating, while in play a hopeless boss absorbs
the shots aimed past it and blocks the mobs behind it. #177, #189 and #196 guard the bot; this measures what the
map still costs it, to decide whether the planner should discount such maps.

Per bot on each boss map (the tools/wz/grind_boss_scan.py list, boss HP of --min-hp or more), over --hours:
- EXP per hour there and on its other grind maps, from the 5 minute EXP log (pairs of samples on the same map,
  grinding, at most 12 minutes apart);
- the share of its grind target picks there and elsewhere that ended with the mob's HP unmoved (the decision log);
- the planner's EXP per hour for the map, read from /api/apdebug for bots on one now.

Usage: boss_map_exp.py [--hours 72] [--min-hp 1000000]
Prints a Markdown section for the nightly report.
"""
import argparse
import collections
import csv
import glob
import json
import re
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "wz"))
import grind_boss_scan  # noqa: E402

EXP_LOG = Path.home() / "Library/Application Support/cosmic-explog/exp-log.csv"
API = "http://localhost:8089"
MAX_GAP_S = 12 * 60
MAX_HOLD_MS = 120_000   # longer holds are breaks or rescues, as in the trainer
MIN_HOURS = 1.0         # hours on a boss map before a bot's row counts toward the verdict
DISCOUNT_RATIO = 0.7    # a boss map earning under this share of the bot's pace elsewhere is a discount candidate


def map_rates(rows, since, boss_maps):
    """{bot: {"boss": {map: [exp, secs]}, "else": [exp, secs]}} from EXP log rows while grinding."""
    by = collections.defaultdict(list)
    for r in rows:
        if int(r["ts"]) >= since:
            by[r["name"]].append(r)
    out = {}
    for name, rs in by.items():
        rs.sort(key=lambda r: int(r["ts"]))
        acc = {"boss": collections.defaultdict(lambda: [0, 0]), "else": [0, 0]}
        for a, b in zip(rs, rs[1:]):
            dt = int(b["ts"]) - int(a["ts"])
            if dt > MAX_GAP_S or a["map"] != b["map"] or a["grinding"] != "1" or b["grinding"] != "1":
                continue
            gain = max(0, int(b["total_exp"]) - int(a["total_exp"]))
            slot = acc["boss"][int(a["map"])] if int(a["map"]) in boss_maps else acc["else"]
            slot[0] += gain
            slot[1] += dt
        out[name] = acc
    return out


def dry_picks(log_dir, since_ms, boss_maps):
    """{bot id: {"boss": {map: [dry, picks]}, "else": [dry, picks]}}: picks whose mob never lost HP."""
    out = collections.defaultdict(lambda: {"boss": collections.defaultdict(lambda: [0, 0]), "else": [0, 0]})
    for path in sorted(glob.glob(str(Path(log_dir) / "grind-target-*.jsonl"))):
        with open(path) as f:
            for line in f:
                try:
                    r = json.loads(line)
                except json.JSONDecodeError:
                    continue
                o = r.get("out")
                if r.get("t", 0) < since_ms or not o or o["holdMs"] <= 0 or o["holdMs"] > MAX_HOLD_MS:
                    continue
                slot = out[r["bot"]]["boss"][r["map"]] if r["map"] in boss_maps else out[r["bot"]]["else"]
                slot[0] += 1 if o.get("mobHpDrop", 0) <= 0 and not o.get("died") else 0
                slot[1] += 1
    return out


def promised(bot_id, map_id):
    """The planner's EXP per hour for map_id, from the bot's live autopilot report; None when unavailable."""
    try:
        text = urllib.request.urlopen(f"{API}/api/apdebug?id={bot_id}", timeout=60).read().decode("utf-8", "replace")
    except OSError:
        return None
    m = re.search(rf"map={map_id}\b.*?exp/hr=([\d,]+)", text)
    return int(m.group(1).replace(",", "")) if m else None


def share(pair):
    return f"{pair[0] / pair[1]:.0%}" if pair[1] else "n/a"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--hours", type=float, default=72.0)
    ap.add_argument("--min-hp", type=int, default=1_000_000)
    ap.add_argument("--wz", default=str(ROOT / "wz"))
    ap.add_argument("--decisions", default=str(ROOT / "logs" / "bot-decisions"))
    args = ap.parse_args(argv)
    bosses = {}
    for r in grind_boss_scan.scan(args.wz):
        if r["hp"] >= args.min_hp and r["hp"] > bosses.get(r["map"], {}).get("hp", 0):
            bosses[r["map"]] = r
    since = int(time.time() - args.hours * 3600)
    with open(EXP_LOG) as f:
        rows = list(csv.DictReader(f))
    ids = {r["name"]: int(r["id"]) for r in rows}
    latest = {}
    for r in rows:
        latest[r["name"]] = r
    rates = map_rates(rows, since, set(bosses))
    dry = dry_picks(args.decisions, since * 1000, set(bosses))

    print(f"\n## EXP on boss maps, last {args.hours:g} h\n")
    lines, flagged = [], []
    for name, acc in sorted(rates.items()):
        for map_id, (exp, secs) in sorted(acc["boss"].items()):
            if secs <= 0:
                continue
            here = exp / secs * 3600
            e_exp, e_secs = acc["else"]
            there = e_exp / e_secs * 3600 if e_secs >= MIN_HOURS * 3600 else None
            now_on = latest[name]["map"] == str(map_id)
            promise = promised(ids[name], map_id) if now_on else None
            d = dry[ids[name]]
            ratio = here / there if there else None
            b = bosses[map_id]
            lines.append(f"| {b['mapName']} | {b['bossName']} {b['hp'] / 1e6:.1f}M | {name} | {secs / 3600:.1f} | "
                         f"{here:,.0f} | {f'{there:,.0f}' if there else 'n/a'} | {f'{ratio:.0%}' if ratio else 'n/a'} | "
                         f"{f'{promise:,}' if promise else 'n/a'} | {share(d['boss'][map_id])} | {share(d['else'])} |")
            if ratio is not None and secs >= MIN_HOURS * 3600 and ratio < DISCOUNT_RATIO:
                flagged.append(f"{name} on {b['mapName']} ({ratio:.0%} of its pace elsewhere)")
    if not lines:
        print("No bot ground on a boss map in the window.")
        return 0
    print("| Map | Boss | Bot | Hours | EXP/h there | EXP/h elsewhere | Ratio | Planner EXP/h | Dry picks there | Dry picks elsewhere |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    print("\n".join(lines))
    print()
    if flagged:
        print("Discount candidates (under " + f"{DISCOUNT_RATIO:.0%}" + " of the bot's pace elsewhere for an hour or more): "
              + "; ".join(flagged) + ".")
    else:
        print("No boss map held a bot under " + f"{DISCOUNT_RATIO:.0%}" + " of its pace elsewhere for an hour; the planner "
              "needs no boss discount yet.")
    print("Planner EXP/h is filled only for bots on the map at report time.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
