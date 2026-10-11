#!/usr/bin/env python3
"""EXP per hour per bot from the EXP log, before and after a change.

Usage: exp_report.py --change "2026-10-09 20:30" [--label "damage fix"] [--log PATH] [--hours 24]
  Splits each bot's logged time at the change and reports EXP per hour on each side. Only gaps of at
  most 12 minutes between samples count (the log runs every 5 minutes; a longer gap is the bot or the
  server being offline), and an EXP drop (death penalty) counts as zero gain, not negative.
"""
import argparse
import csv
import datetime as dt
import os
from collections import defaultdict
from pathlib import Path

DEFAULT_LOG = Path(os.environ.get("EXPLOG_DIR", Path.home() / "Library/Application Support/cosmic-explog")) / "exp-log.csv"
MAX_GAP_S = 12 * 60


def load(path):
    rows = defaultdict(list)
    with open(path) as f:
        for r in csv.DictReader(f):
            rows[r["name"]].append((int(r["ts"]), int(r["total_exp"]), int(r["level"])))
    for v in rows.values():
        v.sort()
    return rows


def rate(samples, start, end):
    """(EXP per hour, hours counted) over [start, end)."""
    gained = seconds = 0
    for (t0, e0, _), (t1, e1, _) in zip(samples, samples[1:]):
        if t0 < start or t1 > end or t1 - t0 > MAX_GAP_S:
            continue
        gained += max(0, e1 - e0)
        seconds += t1 - t0
    return (gained * 3600 / seconds if seconds else None), seconds / 3600


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--change", required=True, help="local time of the change, YYYY-MM-DD HH:MM")
    ap.add_argument("--label", default="change")
    ap.add_argument("--log", default=str(DEFAULT_LOG))
    ap.add_argument("--hours", type=float, default=24, help="window on each side of the change")
    args = ap.parse_args()
    change = int(dt.datetime.strptime(args.change, "%Y-%m-%d %H:%M").timestamp())
    span = int(args.hours * 3600)
    data = load(args.log)
    lines = [f"# EXP per hour around {args.label} ({args.change})", "",
             "| Bot | Level | Before EXP/h | Hours | After EXP/h | Hours | Change |", "|---|---|---|---|---|---|---|"]
    totals = [0.0, 0.0, 0, 0]
    for name in sorted(data, key=lambda n: (len(n), n)):
        s = data[name]
        before, hb = rate(s, change - span, change)
        after, ha = rate(s, change, change + span)
        if before is None and after is None:
            continue
        delta = f"{(after / before - 1) * 100:+.0f}%" if before and after else ""
        fmt = lambda v: f"{v:,.0f}" if v is not None else ""  # noqa: E731
        lines.append(f"| {name} | {s[-1][2]} | {fmt(before)} | {hb:.1f} | {fmt(after)} | {ha:.1f} | {delta} |")
        if before and after:
            totals[0] += before
            totals[1] += after
            totals[2] += 1
    if totals[2]:
        lines += ["", f"Bots measured on both sides: {totals[2]}. Mean EXP/h {totals[0] / totals[2]:,.0f} before, "
                      f"{totals[1] / totals[2]:,.0f} after ({(totals[1] / totals[0] - 1) * 100:+.0f}%)."]
    print("\n".join(lines))


if __name__ == "__main__":
    main()
