"""Compare BotEdgeSweepCli outputs and decide the nav gate.

compare_sweeps.py base.tsv head.tsv candidates.txt probes.txt
    Report stuck moves on both sides and write the moves stuck on head but not on base to candidates.txt.
    Write to probes.txt the trips (map, start, end) of moves that work on base and have no move like them
    on head: a change that prunes or reshapes edges can take away a way between two platforms without
    leaving a stuck move behind, so those trips are run on both sides with the live router choosing.
compare_sweeps.py --confirm base_confirm.tsv head_confirm.tsv [base_probe.tsv head_probe.tsv]
    Each candidate and each trip was run 10 times on each side. A regression fails at least 8 of 10 on
    head and at most 2 of 10 on base. The sim isn't fully deterministic, so a single sweep can't tell a
    regression from a move that fails some of the time on both sides. A missing file counts as empty.

A move is keyed by map, type, launch step and its start and end points, not by region ids, which shift
when a change splits or merges regions. Stuck means the bot stayed on its platform or froze: the trap that
stalls a live bot for hours. Moves that land on another platform than planned cost replanning time, so
they are reported but not gated.
"""
import csv
import os
import sys

STUCK = {"stayed", "frozen"}
HEAD_MIN, BASE_MAX = 8, 2
# A head move counts as the same way as a base move when both ends are this close (px): builder changes
# shift launch and landing points a little without changing where a move goes.
NEAR_START_X, NEAR_END_X, NEAR_Y = 48, 64, 10


def key(r):
    return "\t".join((r["map"], r["type"], r["stepX"], r["startX"], r["startY"], r["endX"], r["endY"]))


def load(path):
    if not os.path.exists(path):
        return {}
    with open(path) as f:
        return {key(r): r for r in csv.DictReader(f, delimiter="\t")}


def count(rows, test):
    return sum(1 for r in rows.values() if test(r["verdict"]))


def ends(r):
    return int(r["startX"]), int(r["startY"]), int(r["endX"]), int(r["endY"])


def near(a, b):
    ax, ay, aex, aey = ends(a)
    bx, by, bex, bey = ends(b)
    return (abs(ax - bx) <= NEAR_START_X and abs(ay - by) <= NEAR_Y
            and abs(aex - bex) <= NEAR_END_X and abs(aey - bey) <= NEAR_Y)


def lost_moves(base, head):
    """Base moves that work and have no head move (of any type or verdict but an error) near both ends."""
    head_by_map = {}
    for r in head.values():
        if not r["verdict"].startswith("error"):
            head_by_map.setdefault(r["map"], []).append(r)
    return [r for _, r in sorted(base.items())
            if r["verdict"] == "ok" and not any(near(r, h) for h in head_by_map.get(r["map"], []))]


def compare(base_path, head_path, candidates_path, probes_path):
    base, head = load(base_path), load(head_path)
    base_stuck = {k for k, r in base.items() if r["verdict"] in STUCK}
    head_stuck = {k for k, r in head.items() if r["verdict"] in STUCK}
    new = sorted(head_stuck - base_stuck)
    print(f"stuck moves: {len(base_stuck)} on base, {len(head_stuck)} on this branch "
          f"({len(base_stuck - head_stuck)} fixed, {len(new)} to confirm)")
    print(f"landing elsewhere (not gated): {count(base, lambda v: v == 'wrong-region')} on base, "
          f"{count(head, lambda v: v == 'wrong-region')} on this branch")
    print(f"flaky, passing on a retry (not gated): {count(base, lambda v: v.startswith('flaky'))} on base, "
          f"{count(head, lambda v: v.startswith('flaky'))} on this branch")
    lost = lost_moves(base, head)
    trips = sorted({"\t".join((r["map"], r["startX"], r["startY"], r["endX"], r["endY"])) for r in lost})
    print(f"working moves on base with no move like them here: {len(lost)} ({len(trips)} trips to probe)")
    with open(candidates_path, "w") as f:
        f.write("".join(k + "\n" for k in new))
    with open(probes_path, "w") as f:
        f.write("".join(t + "\n" for t in trips))
    return 0


def fails(r):
    k, n = r["verdict"].split(":")[1].split("/")
    return int(k), int(n)


def regressions(base, head):
    found = []
    for k, r in sorted(head.items()):
        hk, hn = fails(r)
        bk, bn = fails(base[k]) if k in base else (0, 0)  # a move missing on base is new on this branch
        if hk >= HEAD_MIN * hn / 10 and bk <= BASE_MAX * max(bn, 1) / 10:
            found.append((r, hk, hn, bk, bn))
    return found


def confirm(base_path, head_path, base_probe_path=None, head_probe_path=None):
    head = load(head_path)
    stuck = regressions(load(base_path), head)
    print(f"confirmed regressions: {len(stuck)} of {len(head)} candidates")
    for r, hk, hn, bk, bn in stuck:
        print(f"  map {r['map']} {r['type']} from ({r['startX']},{r['startY']}) to ({r['endX']},{r['endY']}): "
              f"stuck {hk}/{hn} here, {bk}/{bn} on base; bot ended at ({r['finalX']},{r['finalY']})")
    lost = []
    if base_probe_path and head_probe_path:
        head_probe = load(head_probe_path)
        lost = regressions(load(base_probe_path), head_probe)
        print(f"confirmed lost ways: {len(lost)} of {len(head_probe)} trips")
        for r, hk, hn, bk, bn in lost:
            print(f"  map {r['map']} from ({r['startX']},{r['startY']}) to ({r['endX']},{r['endY']}): "
                  f"fails {hk}/{hn} here, {bk}/{bn} on base; bot ended at ({r['finalX']},{r['finalY']})")
    failed = stuck or lost
    print("NAV GATE: FAIL" if failed else "NAV GATE: PASS")
    return 1 if failed else 0


if __name__ == "__main__":
    if sys.argv[1] == "--confirm":
        sys.exit(confirm(*sys.argv[2:6]))
    sys.exit(compare(*sys.argv[1:5]))
