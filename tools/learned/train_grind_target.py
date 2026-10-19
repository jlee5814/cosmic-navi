#!/usr/bin/env python3
"""Stage 2 of the learned grind target scorer (docs/bot/learned-grind-target.md).

Reads the decision log (logs/bot-decisions/grind-target-*.jsonl), fits a small model that predicts the value
of picking a candidate, and checks offline, on held out time, whether ranking by the model would have earned
more than the rules. Writes the model and its verdict as JSON. No dependencies.

    python3 tools/learned/train_grind_target.py logs/bot-decisions [--model linear|trees] [--out model.json]

Two models: ridge regression (linear, the first one), and small gradient boosted trees (trees), which can learn
interactions a weighted sum can't, such as a far mob being worth it when it is nearly dead.

Value of a pick: the EXP it earned minus what its hold time would have earned at the bot's average rate,
so a fast small kill and a slow big one compare fairly (the goal is EXP per hour, not EXP per pick).

The model only reorders what the rules already allowed: on each decision it may choose among the actions
the logging policy could have taken (the rule's first, plus the near equal alternatives a fresh pick could
explore). That keeps every decision it makes evaluable by inverse propensity weighting.
"""
import argparse
import glob
import json
import math
import os
import random
import sys

# Same values as BotCombatManager.cfg and BotGrindTargetLog when the log was written.
EXPLORE = 0.1
EXPLORE_BAND_MS = 1500
MAX_ALTERNATIVES = 2
MAX_HOLD_MS = 120_000  # longer holds are breaks, logouts or rescues, not a pick's own outcome
READY_PICKS, READY_MAPS, READY_EXPLORED = 20_000, 10, 300

FEATURES = ["rank", "route_s", "local_k", "crowd", "lvl_gap", "mob_hp", "adx_100", "dy_100", "rope", "bot_hp"]


def features(rec, i):
    """Candidate i of a decision as the feature vector the Java scorer will compute (stage 3)."""
    c = rec["cands"][i]
    return [
        float(i),
        min(c["route"], 60_000) / 1000.0,
        max(-60_000, min(c["local"], 60_000)) / 1000.0,
        float(c["crowd"]),
        float(c["mobLvl"] - rec["lvl"]),
        float(c["mobHp"]),
        abs(c["dx"]) / 100.0,
        c["dy"] / 100.0,
        1.0 if rec["rope"] else 0.0,
        float(rec["hp"]),
    ]


def support(rec):
    """{candidate index: probability the logging policy gave it}, rebuilt from the logged pick probability.

    p == 1: no exploration was possible (live target, no alternative in band, or off the graph).
    Otherwise the rule's first had 1 - EXPLORE and each in band alternative EXPLORE / n.
    """
    if rec["p"] >= 0.999:
        return {0: 1.0}
    cands = rec["cands"]
    best = cands[0]["route"]
    alts = [i for i in range(1, len(cands)) if cands[i]["route"] <= best + EXPLORE_BAND_MS][:MAX_ALTERNATIVES]
    if not alts:
        return None
    probs = {0: 1.0 - EXPLORE}
    probs.update({i: EXPLORE / len(alts) for i in alts})
    return probs


def load(log_dir):
    recs = []
    for path in sorted(glob.glob(os.path.join(log_dir, "grind-target-*.jsonl"))):
        with open(path) as f:
            for line in f:
                try:
                    recs.append(json.loads(line))
                except json.JSONDecodeError:
                    continue
    return recs


def prepare(recs):
    """Usable decisions with their support and value; drops holds past MAX_HOLD_MS and inconsistent lines."""
    by_bot_exp, by_bot_ms = {}, {}
    kept = []
    for r in recs:
        out = r.get("out")
        if not out or not r.get("cands") or out["holdMs"] <= 0 or out["holdMs"] > MAX_HOLD_MS:
            continue
        sup = support(r)
        if sup is None or r["pick"] not in sup or abs(sup[r["pick"]] - r["p"]) > 0.011:
            continue  # the log does not match the policy we can rebuild; can't weight it honestly
        kept.append((r, sup))
        by_bot_exp[r["bot"]] = by_bot_exp.get(r["bot"], 0) + out["exp"]
        by_bot_ms[r["bot"]] = by_bot_ms.get(r["bot"], 0) + out["holdMs"]
    rate = {b: by_bot_exp[b] / by_bot_ms[b] for b in by_bot_exp if by_bot_ms[b] > 0}  # EXP per ms
    rows = []
    for r, sup in kept:
        value = r["out"]["exp"] - rate.get(r["bot"], 0.0) * r["out"]["holdMs"]
        rows.append({"rec": r, "support": sup, "value": value})
    rows.sort(key=lambda x: x["rec"]["t"])
    return rows


def readiness(recs):
    return {"picks": len(recs), "maps": len({r["map"] for r in recs}),
            "explored": sum(1 for r in recs if r.get("pick", 0) > 0)}


def fit(rows, l2=1.0):
    """Ridge regression of a pick's value on its candidate's standardized features, each row weighted by
    one over its logged probability: explored picks are rare, and they are what shows how alternatives
    compare, so they count as much as the decisions they stand for."""
    xs = [features(r["rec"], r["rec"]["pick"]) for r in rows]
    ys = [r["value"] for r in rows]
    ws = [1.0 / r["support"][r["rec"]["pick"]] for r in rows]
    k = len(FEATURES)
    total = sum(ws)
    mean = [sum(w * x[j] for w, x in zip(ws, xs)) / total for j in range(k)]
    std = [math.sqrt(sum(w * (x[j] - mean[j]) ** 2 for w, x in zip(ws, xs)) / total) or 1.0 for j in range(k)]
    zs = [[(x[j] - mean[j]) / std[j] for j in range(k)] for x in xs]
    ybar = sum(w * y for w, y in zip(ws, ys)) / total
    a = [[sum(w * z[i] * z[j] for w, z in zip(ws, zs)) + (l2 if i == j else 0.0) for j in range(k)]
         for i in range(k)]
    b = [sum(w * z[i] * (y - ybar) for w, z, y in zip(ws, zs, ys)) for i in range(k)]
    w = solve(a, b)
    return {"features": FEATURES, "mean": mean, "std": std, "weights": w, "bias": ybar}


def solve(a, b):
    n = len(b)
    m = [row[:] + [b[i]] for i, row in enumerate(a)]
    for col in range(n):
        piv = max(range(col, n), key=lambda r: abs(m[r][col]))
        m[col], m[piv] = m[piv], m[col]
        if abs(m[col][col]) < 1e-12:
            continue
        for r in range(n):
            if r != col:
                f = m[r][col] / m[col][col]
                m[r] = [x - f * y for x, y in zip(m[r], m[col])]
    return [m[i][n] / m[i][i] if abs(m[i][i]) > 1e-12 else 0.0 for i in range(n)]


# Gradient boosted trees: squared loss on the pick's value, each row weighted by one over its logged
# probability (as the ridge fit), splits found on quantile bins of each feature. Small on purpose: shallow
# trees, a low learning rate and a minimum leaf weight, so the held out gate, not the fit, decides.
TREES, DEPTH, LEARNING_RATE, BINS, MIN_LEAF_WEIGHT, LEAF_L2 = 80, 3, 0.1, 32, 400.0, 1.0


def fit_trees(rows, trees=TREES, depth=DEPTH, lr=LEARNING_RATE, bins=BINS, min_leaf=MIN_LEAF_WEIGHT, l2=LEAF_L2):
    xs = [features(r["rec"], r["rec"]["pick"]) for r in rows]
    ys = [r["value"] for r in rows]
    ws = [1.0 / r["support"][r["rec"]["pick"]] for r in rows]
    k = len(FEATURES)
    cuts = []
    for j in range(k):
        vals = sorted(set(x[j] for x in xs))
        if len(vals) <= bins:
            cuts.append([(a + b) / 2 for a, b in zip(vals, vals[1:])])
        else:
            col = sorted(x[j] for x in xs)
            qs = sorted(set(col[int(len(col) * q / bins)] for q in range(1, bins)))
            cuts.append(qs)
    binned = [[bin_of(cuts[j], x[j]) for j in range(k)] for x in xs]
    total_w = sum(ws)
    base = sum(w * y for w, y in zip(ws, ys)) / total_w
    pred = [base] * len(rows)
    forest = []
    for _ in range(trees):
        resid = [y - p for y, p in zip(ys, pred)]
        tree = grow(binned, resid, ws, list(range(len(rows))), cuts, depth, min_leaf, l2)
        forest.append(tree)
        for n, b in enumerate(binned):
            pred[n] += lr * leaf_of(tree, b)
    return {"kind": "trees", "features": FEATURES, "cuts": cuts, "base": base, "lr": lr, "trees": forest}


def bin_of(cuts, v):
    lo, hi = 0, len(cuts)
    while lo < hi:
        mid = (lo + hi) // 2
        if v <= cuts[mid]:
            hi = mid
        else:
            lo = mid + 1
    return lo


def grow(binned, resid, ws, idx, cuts, depth, min_leaf, l2):
    sw = sum(ws[n] for n in idx)
    sr = sum(ws[n] * resid[n] for n in idx)
    leaf = {"v": sr / (sw + l2)}
    if depth == 0 or sw < 2 * min_leaf:
        return leaf
    best = None  # (gain, feature, bin)
    parent = sr * sr / (sw + l2)
    for j in range(len(cuts)):
        nb = len(cuts[j]) + 1
        hw, hr = [0.0] * nb, [0.0] * nb
        for n in idx:
            b = binned[n][j]
            hw[b] += ws[n]
            hr[b] += ws[n] * resid[n]
        lw = lr_ = 0.0
        for b in range(nb - 1):
            lw += hw[b]
            lr_ += hr[b]
            rw, rr = sw - lw, sr - lr_
            if lw < min_leaf or rw < min_leaf:
                continue
            gain = lr_ * lr_ / (lw + l2) + rr * rr / (rw + l2) - parent
            if best is None or gain > best[0]:
                best = (gain, j, b)
    if best is None or best[0] <= 0:
        return leaf
    _, j, b = best
    left = [n for n in idx if binned[n][j] <= b]
    right = [n for n in idx if binned[n][j] > b]
    return {"f": j, "t": cuts[j][b], "b": b,
            "l": grow(binned, resid, ws, left, cuts, depth - 1, min_leaf, l2),
            "r": grow(binned, resid, ws, right, cuts, depth - 1, min_leaf, l2)}


def leaf_of(tree, binned_row):
    while "v" not in tree:
        tree = tree["l"] if binned_row[tree["f"]] <= tree["b"] else tree["r"]
    return tree["v"]


def tree_value(tree, x):
    """One tree on raw features: the threshold form the Java scorer will use (stage 3)."""
    while "v" not in tree:
        tree = tree["l"] if x[tree["f"]] <= tree["t"] else tree["r"]
    return tree["v"]


def score(model, rec, i):
    x = features(rec, i)
    if model.get("kind") == "trees":
        return model["base"] + model["lr"] * sum(tree_value(t, x) for t in model["trees"])
    return model["bias"] + sum(w * (v - mu) / sd for w, v, mu, sd in
                               zip(model["weights"], x, model["mean"], model["std"]))


def policy_pick(model, row):
    """The model's choice among the logged support; ties go to the rule's first."""
    best, best_score = 0, score(model, row["rec"], 0)
    for i in sorted(row["support"]):
        s = score(model, row["rec"], i)
        if s > best_score + 1e-9:
            best, best_score = i, s
    return best


def snips(rows, picks):
    """Self normalized inverse propensity estimate of the mean value per decision for the given picks."""
    num = den = 0.0
    for row, a in zip(rows, picks):
        if row["rec"]["pick"] == a:
            w = 1.0 / row["support"][a]
            num += w * row["value"]
            den += w
    return num / den if den > 0 else float("nan")


def evaluate(model, rows, boots=1000, seed=7):
    model_picks = [policy_pick(model, r) for r in rows]
    rule_picks = [0] * len(rows)
    diff = snips(rows, model_picks) - snips(rows, rule_picks)
    rnd = random.Random(seed)
    samples = []
    for _ in range(boots):
        idx = [rnd.randrange(len(rows)) for _ in rows]
        rs = [rows[i] for i in idx]
        samples.append(snips(rs, [model_picks[i] for i in idx]) - snips(rs, [0] * len(rs)))
    samples = sorted(s for s in samples if not math.isnan(s))
    lo = samples[int(0.05 * len(samples))] if samples else float("nan")
    hi = samples[int(0.95 * len(samples)) - 1] if samples else float("nan")
    return {
        "decisions": len(rows),
        "differs_from_rule": sum(1 for a in model_picks if a != 0),
        "model_value": snips(rows, model_picks),
        "rule_value": snips(rows, rule_picks),
        "gain": diff,
        "gain_90ci": [lo, hi],
    }


def split_counts(model):
    counts = {}

    def walk(t):
        if "v" in t:
            return
        counts[t["f"]] = counts.get(t["f"], 0) + 1
        walk(t["l"])
        walk(t["r"])
    for t in model["trees"]:
        walk(t)
    return counts


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("log_dir")
    ap.add_argument("--model", choices=["linear", "trees"], default="linear")
    ap.add_argument("--out", default=None, help="default tools/learned/out/grind-target-<model>.json")
    ap.add_argument("--holdout", type=float, default=0.25, help="latest share of decisions held out for the gate")
    args = ap.parse_args(argv)

    recs = load(args.log_dir)
    ready = readiness(recs)
    rows = prepare(recs)
    if len(rows) < 200:
        print(f"only {len(rows)} usable decisions; need at least 200 to fit anything")
        return 2
    cut = int(len(rows) * (1 - args.holdout))
    train, held = rows[:cut], rows[cut:]
    model = fit_trees(train) if args.model == "trees" else fit(train)
    ev = evaluate(model, held)
    data_ready = (ready["picks"] >= READY_PICKS and ready["maps"] >= READY_MAPS
                  and ready["explored"] >= READY_EXPLORED)
    passes = data_ready and ev["gain_90ci"][0] > 0
    model.update({"readiness": ready, "data_ready": data_ready, "train_decisions": len(train), "eval": ev,
                  "passes_gate": passes})
    out = args.out or f"tools/learned/out/grind-target-{args.model}.json"
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    with open(out, "w") as f:
        json.dump(model, f, indent=2 if args.model == "linear" else None)

    print(f"log: {ready['picks']} picks, {ready['maps']} maps, {ready['explored']} explored "
          f"(gate {READY_PICKS}, {READY_MAPS}, {READY_EXPLORED}: {'met' if data_ready else 'not yet'})")
    print(f"usable: {len(rows)} decisions; train {len(train)}, held out {len(held)}")
    if args.model == "linear":
        print("weights (per standard deviation):")
        for name, w in sorted(zip(FEATURES, model["weights"]), key=lambda t: -abs(t[1])):
            print(f"  {name:8s} {w:+9.1f}")
    else:
        used = split_counts(model)
        print(f"{len(model['trees'])} trees of depth {DEPTH}; splits per feature:",
              ", ".join(f"{FEATURES[j]} {c}" for j, c in sorted(used.items(), key=lambda t: -t[1])))
    print(f"held out: model differs from the rule on {ev['differs_from_rule']} of {ev['decisions']} decisions")
    print(f"value per decision (EXP above the bot's average pace): rule {ev['rule_value']:+.1f}, "
          f"model {ev['model_value']:+.1f}, gain {ev['gain']:+.1f} (90% CI {ev['gain_90ci'][0]:+.1f} "
          f"to {ev['gain_90ci'][1]:+.1f})")
    print("verdict:", "PASS, ready for stage 3" if passes else
          "not yet: " + ("more data needed" if not data_ready else "gain not above noise"))
    print("wrote", out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
