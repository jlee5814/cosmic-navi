#!/usr/bin/env python3
"""Stage 3 live check of the learned grind target scorer: is the model arm of the fleet doing better than the
rule arm? (docs/bot/learned-grind-target.md)

    python3 tools/learned/compare_arms.py logs/bot-decisions [--since EPOCH_MS] [--before-hours 24]

Each bot is in one arm for good (a stable hash of its id), and the value the trainer uses is measured against
the bot's own pace, so it can't compare arms. This compares each bot with itself instead: its EXP per hour of
grinding after the split against the same bot's rate in the window before, then the model arm's change
against the rule arm's (a difference in differences). A 90% interval comes from resampling bots, since bots,
not picks, are the independent units. Also reports, per arm, the share of picks that ended with no hit.

The split starts at the first line that names an arm, unless --since says otherwise. A bot is in the model
arm when any of its lines since then were chosen by the model.

With few grinding bots that comparison is weak, so it also replays the decisions since the split, the
trainer's way: every line logs the probability of each pick its policy allowed, so inverse propensity
weighting estimates, per decision, what the served model and the rule would each have earned, across both
arms' lines. Decisions are many even when bots are few.
"""
import argparse
import glob
import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import train_grind_target as trainer  # noqa: E402

MAX_HOLD_MS = 120_000  # longer holds are breaks, logouts or rescues, not grinding (as the trainer)
MIN_GRIND_MS = 30 * 60_000  # a bot needs half an hour of grinding on each side to count


def load(log_dir):
    for path in sorted(glob.glob(os.path.join(log_dir, "grind-target-*.jsonl"))):
        with open(path) as f:
            for line in f:
                try:
                    yield json.loads(line)
                except json.JSONDecodeError:
                    continue


def per_bot(recs, since, before_ms):
    """{bot: {"before": [exp, ms], "after": [exp, ms], "model": bool, "picks": n, "no_hit": n}}."""
    bots = {}
    for r in recs:
        out = r.get("out")
        if not out or out["holdMs"] <= 0 or out["holdMs"] > MAX_HOLD_MS:
            continue
        t = r["t"]
        if t < since - before_ms:
            continue
        b = bots.setdefault(r["bot"], {"before": [0, 0], "after": [0, 0], "model": False, "picks": 0, "no_hit": 0})
        side = "after" if t >= since else "before"
        b[side][0] += out["exp"]
        b[side][1] += out["holdMs"]
        if side == "after":
            b["model"] |= r.get("arm") == "model"
            b["picks"] += 1
            b["no_hit"] += 1 if not out.get("died") and out.get("mobHpDrop", 0) <= 0 else 0
    return bots


def change(b):
    """A bot's EXP per hour after over before, minus one; None without enough grinding on either side."""
    (be, bm), (ae, am) = b["before"], b["after"]
    if bm < MIN_GRIND_MS or am < MIN_GRIND_MS or be <= 0:
        return None
    return (ae / am) / (be / bm) - 1.0


def summarize(bots, boots=2000, seed=3):
    arms = {"model": [], "rule": []}
    for bot, b in bots.items():
        c = change(b)
        if c is not None:
            arms["model" if b["model"] else "rule"].append((bot, c, b))
    mean = lambda xs: sum(xs) / len(xs) if xs else float("nan")
    out = {}
    for arm, rows in arms.items():
        picks = sum(b["picks"] for _, _, b in rows)
        out[arm] = {"bots": len(rows), "mean_change": mean([c for _, c, _ in rows]),
                    "no_hit_share": sum(b["no_hit"] for _, _, b in rows) / picks if picks else float("nan")}
    did = out["model"]["mean_change"] - out["rule"]["mean_change"]
    rnd = random.Random(seed)
    samples = []
    m, r = [c for _, c, _ in arms["model"]], [c for _, c, _ in arms["rule"]]
    if len(m) >= 2 and len(r) >= 2:
        for _ in range(boots):
            samples.append(mean([rnd.choice(m) for _ in m]) - mean([rnd.choice(r) for _ in r]))
        samples.sort()
    out["difference"] = did
    out["difference_90ci"] = [samples[int(0.05 * len(samples))], samples[int(0.95 * len(samples)) - 1]] \
        if samples else [float("nan"), float("nan")]
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("log_dir")
    ap.add_argument("--since", type=int, default=None, help="split start, epoch ms (default: first arm line)")
    ap.add_argument("--before-hours", type=float, default=24.0)
    ap.add_argument("--model", default="models/grind-target-model.json", help="the served model, for the replay")
    args = ap.parse_args(argv)
    recs = list(load(args.log_dir))
    since = args.since or min((r["t"] for r in recs if "arm" in r), default=None)
    if since is None:
        print("no line names an arm yet: the split hasn't started")
        return 2
    s = summarize(per_bot(recs, since, int(args.before_hours * 3_600_000)))
    for arm in ("model", "rule"):
        a = s[arm]
        print(f"{arm:5s}: {a['bots']} bots, EXP/h change {a['mean_change']:+.1%}, picks with no hit {a['no_hit_share']:.1%}")
    lo, hi = s["difference_90ci"]
    print(f"model minus rule: {s['difference']:+.1%} (90% CI {lo:+.1%} to {hi:+.1%})")
    verdict = "keep the model" if lo > 0 else "not shown yet" if hi > 0 else "model is worse: set GRIND_TARGET_MODEL_SHARE to 0"
    print("bots verdict:", verdict)
    if os.path.isfile(args.model):
        with open(args.model) as f:
            model = json.load(f)
        rows = trainer.prepare([r for r in recs if r["t"] >= since])
        if len(rows) >= 200:
            ev = trainer.evaluate(model, rows)
            lo, hi = ev["gain_90ci"]
            print(f"replay of {ev['decisions']} decisions since the split ({model.get('version', 'unversioned')}): "
                  f"rule {ev['rule_value']:+.1f}, model {ev['model_value']:+.1f}, gain {ev['gain']:+.1f} "
                  f"(90% CI {lo:+.1f} to {hi:+.1f})")
            print("replay verdict:", "the model earns more" if lo > 0 else "not shown yet" if hi > 0
                  else "the model earns less: set GRIND_TARGET_MODEL_SHARE to 0")
        else:
            print(f"replay: only {len(rows)} usable decisions since the split")
    return 0


if __name__ == "__main__":
    sys.exit(main())
