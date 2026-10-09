#!/usr/bin/env python3
"""Bot chat quality probe: echoes, speaker labels and a few checkable facts, per model.

Builds messages the way PromptBuilder.buildChat does (shared rules, then identity and game state in
the system message; history as real user/assistant turns; the newest line as a plain user message)
and sends each owner line several times with different sampling seeds.
Usage: chat_quality_probe.py <base_url> <label> [seeds]
"""
import difflib
import json
import re
import statistics
import sys
import time
import urllib.request

RULES = ("You are a real human MapleStory player. Talk like mmo chatter: lowercase, 1 short sentence, "
         "abbreviate whenever possible, text only, use internet slang when applicable, no emoji, no speaker "
         "label. Keep replies simple. Prefer fragments over full grammar. Answer only the latest message "
         "directly. If unsure, say so briefly instead of guessing. Never say you are an ai, assistant, "
         "chatbot, or bot. You are talking to your close gaming friend and party leader Sipsaeki. "
         "Be friendly and helpful.")
STATE = ("Your IGN is SipsBuddy1. You are a real human MapleStory player, level 50 il wizard.\n"
         "[Where you are now]\nMap: Toy Factory <Process 1> Zone 3\nStatus: grinding\nLevel 50, 10% to next\n"
         "Mesos: 1,240,000\nMobs around: Roloduck lv34 x25, Panda Teddy lv36 x25\n"
         "When the game state above disagrees with something you said earlier, the game state is right.")
HISTORY = [("yo", "yo whats up"), ("hows the grind", "goin good, teddies everywhere")]
# (owner line, regex a correct reply should match, or None when there is nothing to check)
LINES = [
    ("can you give me a party invite", None),
    ("what level are you", r"\b50\b"),
    ("how much exp % left", r"\b(10|90)\s*%?"),
    ("where are you grinding", r"toy|factory|teddy|teddies|roloduck"),
    ("how many mesos do you have", r"1[.,]?2|1\.24|1,240|mil"),
    ("did you max lightning", None),
    ("wanna go to henesys later", None),
    ("lol nice", None),
    ("what mobs are you killing", r"teddy|teddies|roloduck|duck"),
    ("brb gonna eat", None),
]


def messages(line):
    msgs = [{"role": "system", "content": RULES + "\n\n" + STATE}]
    for q, a in HISTORY:
        msgs += [{"role": "user", "content": q}, {"role": "assistant", "content": a}]
    return msgs + [{"role": "user", "content": line}]


def ask(base, line, seed):
    body = {"model": "default", "messages": messages(line), "max_tokens": 24, "temperature": 1.0, "top_p": 0.95,
            "top_k": 20, "presence_penalty": 1.5, "sampling_seed": seed,
            "chat_template_kwargs": {"enable_thinking": False}}
    req = urllib.request.Request(base + "/v1/chat/completions", json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    t0 = time.perf_counter()
    with urllib.request.urlopen(req, timeout=60) as r:
        text = json.load(r)["choices"][0]["message"]["content"].strip()
    return text, (time.perf_counter() - t0) * 1000


def norm(s):
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9 ]", " ", s.lower())).strip()


def main():
    base, label = sys.argv[1], sys.argv[2]
    seeds = int(sys.argv[3]) if len(sys.argv) > 3 else 5
    echo = labels = checked = correct = 0
    lat, samples = [], {}
    for line, pattern in LINES:
        for seed in range(seeds):
            text, ms = ask(base, line, 7000 + seed)
            lat.append(ms)
            samples.setdefault(line, []).append(text)
            if len(norm(line)) >= 12 and difflib.SequenceMatcher(None, norm(text), norm(line)).ratio() > 0.8:
                echo += 1
            if re.match(r"^\s*(sipsbuddy1|sipsaeki)\s*:", text, re.I):
                labels += 1
            if pattern:
                checked += 1
                correct += bool(re.search(pattern, text, re.I))
    n = len(LINES) * seeds
    print(json.dumps({"model": label, "replies": n, "echo": echo, "speaker_label": labels,
                      "facts_correct": f"{correct}/{checked}", "latency_p50_ms": round(statistics.median(lat)),
                      "samples": {k: v[:3] for k, v in samples.items()}}, indent=1))


if __name__ == "__main__":
    main()
