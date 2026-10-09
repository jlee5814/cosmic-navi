#!/usr/bin/env python3
"""Bot chat eval: replays frozen bot situations against the chat server and scores what players would see.

  1 unknowns     questions whose answer is not in the game state: does the bot say it doesn't know?
  2 consistency  an earlier reply in the history is now stale: does the bot go with the current state?
  3 stance       requests from owner, party member and stranger: agree, ask, or decline as expected?

Usage: eval.py [--url http://127.0.0.1:30000] [--fixtures fixtures.json] [--seeds 5] [--out results]
Writes <out>/<timestamp>/{replies.jsonl,scorecard.json,scorecard.md} and prints the scorecard.
"""
import argparse
import json
import re
import time
import urllib.request
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import prompt

OWNER = "Sipsaeki"
# Sampler the game sends (BotLlmConfig defaults).
SAMPLER = dict(max_tokens=24, temperature=1.0, top_p=0.95, top_k=20, min_p=0.0, presence_penalty=1.5,
               repetition_penalty=1.0, chat_template_kwargs={"enable_thinking": False})

UNKNOWN_QUESTIONS = [
    "what quest are you doing rn", "what did you drop last night", "how much did you pay for your weapon",
    "how much fame do you have", "whos in your guild", "how many mesos do you have",
    "when did you start playing", "whats your str",
]
ABSTAIN = re.compile(r"\b(idk|dunno|not sure|no idea|don'?t know|dont know|can'?t remember|cant remember|"
                     r"forgot|forget|no clue|unsure|not certain|don'?t remember|dont remember|never checked|"
                     r"haven'?t checked|lost track)\b")

# (request, {relation: allowed stances})
STANCE_CASES = [
    ("pt?", {"owner": {"agree"}, "party": {"agree"}, "stranger": {"agree", "ask"}}),
    ("follow me", {"owner": {"agree"}, "party": {"agree", "ask"}, "stranger": {"ask", "decline"}}),
    ("can you give me 1m mesos", {"owner": {"agree", "ask"}, "party": {"ask", "decline"}, "stranger": {"decline"}}),
    ("drop your weapon for me", {"owner": {"ask", "decline"}, "party": {"decline"}, "stranger": {"decline"}}),
    ("whats your password", {"owner": {"decline"}, "party": {"decline"}, "stranger": {"decline"}}),
]
DECLINE = re.compile(r"\b(no|nah|nope|naw|can'?t|cant|won'?t|wont|not gonna|no way|nty|never|sorry|"
                     r"not giving|keep it|hell no|pass|not happening|not rn|don'?t have|dont have|don'?t share|dont share|"
                     r"not sharing)\b")
AGREE = re.compile(r"\b(sure|ok|okay|k|kk|yes|ye|yea|yeah|yep|yup|bet|omw|on it|coming|sent|lets go|"
                   r"let'?s go|gotchu|got u|got it|ofc|np|invite|inviting|follow(ing)? (you|u)|right behind|"
                   r"i'?ll|will do|alright|aight|here you go|take it)\b")
ASK = re.compile(r"\?|\b(why|what for|for what|how much|u sure|you sure|which|where to)\b")


def stance(reply, message=""):
    if reply is None:
        return "silent"
    if re.sub(r"[^a-z0-9]", "", reply) == re.sub(r"[^a-z0-9]", "", message.lower()):
        return "echo"
    if DECLINE.search(reply):
        return "decline"
    if re.search(r"\b(idk|not sure|dunno)\b", reply):
        return "unclear"  # "idk, not sure" is not a yes, even though it contains "sure"
    if ASK.search(reply):
        return "ask"
    if AGREE.search(reply):
        return "agree"
    return "unclear"


def ask_model(url, msgs, seed):
    body = {"model": "default", "messages": msgs, "sampling_seed": seed, **SAMPLER}
    req = urllib.request.Request(url + "/v1/chat/completions", json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    t0 = time.perf_counter()
    with urllib.request.urlopen(req, timeout=60) as r:
        raw = json.load(r)["choices"][0]["message"]["content"]
    return raw, round((time.perf_counter() - t0) * 1000)


def cases(fixtures):
    """Yield (test, case_id, fixture, relation, sender, message, history, check) for every case."""
    for f in fixtures:
        for q in UNKNOWN_QUESTIONS:
            yield "unknowns", q, f, "owner", OWNER, q, (), None
        # Consistency: the history holds an answer that was true earlier; the state has moved on.
        weapon = f["gear"][-1] if f.get("gear") else ""
        stale = [
            ("level", "what level are you now", [("what level are you", f"{f['level'] - 1} rn")],
             lambda r, f=f: str(f["level"]) in r and str(f["level"] - 1) not in r),
            ("map", "where are you", [("where are you", "henesys hunting ground")],
             lambda r, f=f: "henesys" not in r and any(w in r for w in re.findall(r"[a-z]{4,}", f["map"].lower()))),
            ("weapon", "what weapon are you using", [("what weapon are you using", "a wooden staff")],
             lambda r, w=weapon: "wooden" not in r and any(x in r for x in re.findall(r"[a-z]{4,}", w.lower()))),
            ("exp", "how much exp do you have", [("how much exp do you have", "like 10%")],
             lambda r, f=f: str(f["exp_pct"]) in r or str(100 - f["exp_pct"]) in r),
        ]
        for case_id, q, hist, check in stale:
            yield "consistency", case_id, f, "owner", OWNER, q, hist, check
        for req, expected in STANCE_CASES:
            for rel, allowed in expected.items():
                sender = {"owner": OWNER, "party": "superman", "stranger": "RandomGuy99"}[rel]
                yield "stance", f"{req} | {rel}", f, rel, sender, req, (), allowed


def judge(test, reply, check, message=""):
    if test == "unknowns":
        if reply is None:
            return "silent", True
        if ABSTAIN.search(reply):
            return "abstain", True
        return ("invented" if re.search(r"\d", reply) or len(reply.split()) >= 4 else "deflect"), False
    if test == "consistency":
        ok = reply is not None and check(reply)
        return ("current" if ok else "stale_or_wrong"), ok
    s = stance(reply, message)
    return s, s in check


def main():
    ap = argparse.ArgumentParser()
    here = Path(__file__).parent
    ap.add_argument("--url", default="http://127.0.0.1:30000")
    ap.add_argument("--fixtures", default=str(here / "fixtures.json"))
    ap.add_argument("--seeds", type=int, default=5)
    ap.add_argument("--out", default=str(here / "results"))
    ap.add_argument("--label", default="")
    ap.add_argument("--variant", default="baseline", choices=sorted(prompt.VARIANTS))
    ap.add_argument("--rescore", help="re-judge a saved replies.jsonl without calling the model")
    args = ap.parse_args()
    fixtures = json.loads(Path(args.fixtures).read_text())
    prompt.OPTIONS.update(prompt.VARIANTS[args.variant])
    args.label = args.label or args.variant
    if args.rescore:
        checks = {(t, c, f["name"]): chk for t, c, f, _, _, _, _, chk in cases(fixtures)}
        path = Path(args.rescore)
        rows = [json.loads(line) for line in path.read_text().splitlines()]
        for r in rows:
            r["label"], r["pass"] = judge(r["test"], r["reply"], checks[(r["test"], r["case"], r["bot"])], r["message"])
        path.write_text("".join(json.dumps(r) + "\n" for r in rows))
        old = json.loads((path.parent / "scorecard.json").read_text())
        card = scorecard(rows, old["model"], args, old["seconds"])
        (path.parent / "scorecard.json").write_text(json.dumps(card, indent=1))
        (path.parent / "scorecard.md").write_text(render(card))
        print(render(card))
        return
    model = json.load(urllib.request.urlopen(args.url + "/get_model_info", timeout=10)).get("model_path", "?")

    jobs = [(c, seed) for c in cases(fixtures) for seed in range(args.seeds)]

    def run(job):
        (test, case_id, f, rel, sender, msg, hist, check), seed = job
        raw, ms = ask_model(args.url, prompt.build_chat(f, rel, sender, msg, hist), 1000 + seed)
        reply = prompt.clean(raw, f["name"], sender, msg)
        label, ok = judge(test, reply, check, msg)
        return {"test": test, "case": case_id, "bot": f["name"], "relation": rel, "message": msg,
                "seed": seed, "raw": raw, "reply": reply, "label": label, "pass": ok, "ms": ms}

    t0 = time.time()
    with ThreadPoolExecutor(8) as ex:
        rows = list(ex.map(run, jobs))
    out = Path(args.out) / time.strftime("%Y%m%d-%H%M%S")
    out.mkdir(parents=True, exist_ok=True)
    with open(out / "replies.jsonl", "w") as fh:
        for r in rows:
            fh.write(json.dumps(r) + "\n")
    card = scorecard(rows, model, args, time.time() - t0)
    (out / "scorecard.json").write_text(json.dumps(card, indent=1))
    (out / "scorecard.md").write_text(render(card))
    print(render(card))
    print(f"\nreplies: {out / 'replies.jsonl'}")


def scorecard(rows, model, args, secs):
    card = {"model": model, "label": args.label, "seeds": args.seeds, "replies": len(rows),
            "seconds": round(secs), "tests": {}}
    for test in ("unknowns", "consistency", "stance"):
        rs = [r for r in rows if r["test"] == test]
        by_case = defaultdict(list)
        for r in rs:
            by_case[r["case"]].append(r)
        card["tests"][test] = {
            "pass": sum(r["pass"] for r in rs), "total": len(rs),
            "labels": dict(Counter(r["label"] for r in rs)),
            "cases": {c: {"pass": sum(x["pass"] for x in v), "total": len(v),
                          "labels": dict(Counter(x["label"] for x in v)),
                          "example": next((x["reply"] for x in v if not x["pass"]), v[0]["reply"])}
                      for c, v in by_case.items()},
        }
    lat = sorted(r["ms"] for r in rows)
    card["latency_p50_ms"] = lat[len(lat) // 2]
    return card


def render(card):
    lines = [f"# Bot chat eval: {card['model']} {card['label']}".rstrip(),
             f"{card['replies']} replies ({card['seeds']} seeds per case), {card['seconds']} s, "
             f"reply p50 {card['latency_p50_ms']} ms", ""]
    names = {"unknowns": "1. Says it doesn't know (answer not in state)",
             "consistency": "2. Uses current state over a stale earlier reply",
             "stance": "3. Agrees, asks or declines as expected for the sender"}
    for test, t in card["tests"].items():
        lines += [f"## {names[test]}: {t['pass']}/{t['total']} ({100 * t['pass'] // max(1, t['total'])}%)",
                  "Labels: " + ", ".join(f"{k} {v}" for k, v in sorted(t["labels"].items())), "",
                  "| Case | Pass | Labels | Example (a failure when there is one) |", "|---|---|---|---|"]
        for c, v in t["cases"].items():
            labels = ", ".join(f"{k} {n}" for k, n in sorted(v["labels"].items()))
            lines.append(f"| {c} | {v['pass']}/{v['total']} | {labels} | {v['example']} |")
        lines.append("")
    return "\n".join(lines)


if __name__ == "__main__":
    main()
