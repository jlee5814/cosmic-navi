"""Compare BotEdgeSweepCli outputs and decide the nav gate.

compare_sweeps.py base.tsv head.tsv candidates.txt
    Report stuck moves on both sides and write the moves stuck on head but not on base to candidates.txt.
compare_sweeps.py --confirm base_confirm.tsv head_confirm.tsv
    Each candidate was run 10 times on each side. A regression fails at least 8 of 10 on head and at most
    2 of 10 on base. The sim isn't fully deterministic, so a single sweep can't tell a regression from a
    move that fails some of the time on both sides.

A move is keyed by map, type, launch step and its start and end points, not by region ids, which shift
when a change splits or merges regions. Stuck means the bot stayed on its platform or froze: the trap that
stalls a live bot for hours. Moves that land on another platform than planned cost replanning time, so
they are reported but not gated.
"""
import csv
import sys

STUCK = {"stayed", "frozen"}
HEAD_MIN, BASE_MAX = 8, 2


def key(r):
    return "\t".join((r["map"], r["type"], r["stepX"], r["startX"], r["startY"], r["endX"], r["endY"]))


def load(path):
    return {key(r): r for r in csv.DictReader(open(path), delimiter="\t")}


def count(rows, test):
    return sum(1 for r in rows.values() if test(r["verdict"]))


def compare(base_path, head_path, candidates_path):
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
    with open(candidates_path, "w") as f:
        f.write("".join(k + "\n" for k in new))
    return 0


def fails(r):
    k, n = r["verdict"].split(":")[1].split("/")
    return int(k), int(n)


def confirm(base_path, head_path):
    base, head = load(base_path), load(head_path)
    regressions = []
    for k, r in sorted(head.items()):
        hk, hn = fails(r)
        bk, bn = fails(base[k]) if k in base else (0, 0)  # a move missing on base is new on this branch
        if hk >= HEAD_MIN * hn / 10 and bk <= BASE_MAX * max(bn, 1) / 10:
            regressions.append((r, hk, hn, bk, bn))
    print(f"confirmed regressions: {len(regressions)} of {len(head)} candidates")
    for r, hk, hn, bk, bn in regressions:
        print(f"  map {r['map']} {r['type']} from ({r['startX']},{r['startY']}) to ({r['endX']},{r['endY']}): "
              f"stuck {hk}/{hn} here, {bk}/{bn} on base; bot ended at ({r['finalX']},{r['finalY']})")
    print("NAV GATE: FAIL" if regressions else "NAV GATE: PASS")
    return 1 if regressions else 0


if __name__ == "__main__":
    if sys.argv[1] == "--confirm":
        sys.exit(confirm(sys.argv[2], sys.argv[3]))
    sys.exit(compare(sys.argv[1], sys.argv[2], sys.argv[3]))
