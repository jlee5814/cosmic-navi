"""Rank bots by the EXP the fleet lost on them, so fixes get picked by cost.

For each bot still training, EXP per hour comes from the 5 minute EXP log (gaps over 12 minutes, such as
the Mac asleep, are skipped). The bar is the median of the other training bots within LEVEL_BAND levels
(higher levels earn more EXP, so a fleet wide median would flag every low bot), or the fleet median when
fewer than MIN_PEERS peers exist. Lost EXP = (bar - rate) x hours observed, floored at zero. Each row
carries its evidence: the share of samples that earned nothing, stuck sightings, and how often the bot
shuttled between the same two maps (an errand loop like SipsBuddy26's subway rides).

Usage: lost_exp.py <snapshot.json> [--hours 24] [--top 8]
Prints a Markdown section for the nightly report.
"""
import argparse
import collections
import csv
import json
import statistics
import time
from pathlib import Path

EXP_LOG = Path.home() / "Library/Application Support/cosmic-explog/exp-log.csv"
HISTORY = Path(__file__).resolve().parent / "runs" / "nightly" / "stuck-history.jsonl"
MAX_GAP_S = 12 * 60
LEVEL_BAND = 5
MIN_PEERS = 3
MIN_HOURS = 1.0


def bot_rates(rows, since):
    """name -> dict(rate, hours, zero_share, level, pingpong) from the EXP log."""
    by = collections.defaultdict(list)
    for r in rows:
        if int(r["ts"]) >= since:
            by[r["name"]].append(r)
    out = {}
    for name, rs in by.items():
        rs.sort(key=lambda r: int(r["ts"]))
        gain = secs = zero = 0
        for a, b in zip(rs, rs[1:]):
            dt = int(b["ts"]) - int(a["ts"])
            if dt > MAX_GAP_S:
                continue
            d = max(0, int(b["total_exp"]) - int(a["total_exp"]))  # a death costs EXP; count it as no gain
            secs += dt
            gain += d
            zero += dt if d == 0 else 0
        if secs / 3600 < MIN_HOURS:
            continue
        maps = [r["map"] for r in rs]
        pingpong = sum(1 for i in range(2, len(maps)) if maps[i] == maps[i - 2] != maps[i - 1])
        out[name] = {"rate": gain / secs * 3600, "hours": secs / 3600, "zero_share": zero / secs,
                     "level": int(rs[-1]["level"]), "pingpong": pingpong}
    return out


def stuck_counts(since):
    counts = collections.Counter()
    maps = collections.defaultdict(set)
    if HISTORY.exists():
        for line in HISTORY.read_text().splitlines():
            if not line.strip():
                continue
            s = json.loads(line)
            if time.mktime(time.strptime(s["taken_at"], "%Y-%m-%d %H:%M")) >= since:
                counts[s["bot"]] += 1
                maps[s["bot"]].add(s["map_name"])
    return counts, maps


def rank(rates, training):
    """[(name, info, bar, lost)] for training bots, most EXP lost first."""
    active = {n: v for n, v in rates.items() if n in training}
    if not active:
        return [], None
    fleet_median = statistics.median(v["rate"] for v in active.values())
    rows = []
    for name, v in active.items():
        peers = [p["rate"] for n, p in active.items() if n != name and abs(p["level"] - v["level"]) <= LEVEL_BAND]
        bar = statistics.median(peers) if len(peers) >= MIN_PEERS else fleet_median
        lost = max(0.0, bar - v["rate"]) * v["hours"]
        rows.append((name, v, bar, lost))
    rows.sort(key=lambda r: -r[3])
    return rows, fleet_median


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("snapshot")
    ap.add_argument("--hours", type=float, default=24)
    ap.add_argument("--top", type=int, default=8)
    args = ap.parse_args()
    snap = json.loads(Path(args.snapshot).read_text())
    # Training bots only: parked and capped bots earn nothing by design.
    training = {b["name"] for b in snap["bots"] if not b.get("cap_reached")}
    since = time.time() - args.hours * 3600
    rates = bot_rates(list(csv.DictReader(EXP_LOG.open())), since)
    rows, fleet_median = rank(rates, training)
    counts, stuck_maps = stuck_counts(since)
    print(f"\n## Lost EXP, last {args.hours:g} h\n")
    if not rows:
        print("- No training bot has an hour of EXP log in this window.")
        return
    total_lost = sum(r[3] for r in rows)
    total_rate = sum(r[1]["rate"] for r in rows)
    print(f"Fleet median {fleet_median:,.0f} EXP/h over {len(rows)} training bots. "
          f"Lost against the level band median: {total_lost:,.0f} EXP, about "
          f"{total_lost / max(1.0, total_rate * statistics.median(r[1]['hours'] for r in rows)):.0%} of what the fleet earned.\n")
    print("| Bot | Level | EXP/h | Band median | Hours | Lost EXP | Earned nothing | Evidence |")
    print("|---|---|---|---|---|---|---|---|")
    for name, v, bar, lost in rows[:args.top]:
        if lost <= 0:
            break
        evidence = []
        if counts[name]:
            evidence.append(f"stuck {counts[name]}x ({', '.join(sorted(stuck_maps[name]))})")
        if v["pingpong"] >= 4:
            evidence.append(f"shuttled between two maps {v['pingpong']}x")
        print(f"| {name} | {v['level']} | {v['rate']:,.0f} | {bar:,.0f} | {v['hours']:.1f} | {lost:,.0f} | "
              f"{v['zero_share']:.0%} | {'; '.join(evidence) or ''} |")


if __name__ == "__main__":
    main()
