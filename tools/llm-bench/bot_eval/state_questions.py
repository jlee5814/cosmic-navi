"""Test 4: does the classify call (server.bots.llm.BotTurn) name the state question an owner's line asks,
and leave chat alone? Lines are scored by label, not by reply text.

The question names, hints and prompt examples are read from the Java source, so they cannot drift; the
prompt sentence around them is copied from BotTurn.prompt and must change when it does.

    python3 state_questions.py                 # both line sets, needs the chat server on :30000
    python3 state_questions.py --set held_out  # only lines unlike the prompt's examples
"""
import argparse
import json
import re
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BOTS = ROOT / "src/main/java/server/bots"
QUESTIONS = re.findall(r'STATE_QUESTIONS\.put\("(\w+)"', (BOTS / "BotChatManager.java").read_text())
TURN = (BOTS / "llm/BotTurn.java").read_text()
HINTS = dict(re.findall(r'"(\w+)", "([^"]+)"', TURN[TURN.index("HINTS = Map.of("):TURN.index(");", TURN.index("HINTS"))]))
EXAMPLES = re.findall(r'new String\[\] \{"([^"]+)", (?:"(\w+)"|null)\}', TURN)

# Same reports answer these, so either label gives the right reply ("lv70, 7% to 71" answers both).
SAME = {frozenset(("exp", "level"))}

LINES = {
    "tuning": [
        ("yo how loaded are u these days", "mesos"), ("how much cash u got", "mesos"), ("u rich yet?", "mesos"),
        ("wat lvl r u now", "level"), ("how close to 71", "exp"), ("how much xp till next", "exp"),
        ("which map u on rn", "where"), ("u still in ludi?", "where"), ("whatcha up to", "doing"),
        ("how many pots do u have left", "pots"), ("bag full?", "slots"), ("whats ur str at", "stats"),
        ("how hard do u hit", "damage"), ("what skills u maxed", "skills"),
        ("lol that orc almost got me", None), ("gg", None), ("brb food", None), ("u wanna pq later?", None),
        ("nice job on that boss", None), ("i just leveled!", None), ("ludi music is so good", None),
        ("thanks for the heal", None), ("where should we grind next", None), ("how was ur day", None),
    ],
    "held_out": [
        ("how much money u sitting on", "mesos"), ("r u broke", "mesos"), ("what lv u at", "level"),
        ("u almost 71?", "level"), ("where u grinding rn", "where"), ("what map is this", "where"),
        ("busy?", "doing"), ("what r u working on", "doing"), ("got mana pots?", "pots"),
        ("how much room in ur bag", "slots"), ("any scrolls on u", "scrolls"), ("ur int high?", "stats"),
        ("whats ur dmg range", "damage"), ("did u max heal yet", "skills"), ("what u carrying", "inventory"),
        ("lmao", None), ("ty", None), ("wanna go to orbis", None), ("that drop was insane", None),
        ("im lv 50 now", None), ("where do toy trojans spawn", None), ("u think ill get my 2nd job today?", None),
        ("u like this game?", None), ("party up", None),
    ],
}

SELF = re.compile(r"\b(?:i|im|i'm|ive|i've|my|me|mine)\b")
ADDRESSED = re.compile(r"\b(?:u|you|ur|your|ya|yours|r)\b")


def prompt():
    named = ", ".join(HINTS.get(q, q) for q in QUESTIONS)
    examples = "".join(f" {line} -> " + (f"true, {label}." if label else "false, chat.") for line, label in EXAMPLES)
    return ("You read chat messages in an online game. A player sent the message below to a friend. "
            "fact_question: true only if the message asks the friend for a fact about their own character right now "
            f"({named}). "
            "Reactions, greetings, plans, invitations, news about the sender and small talk are false. "
            "ask: the fact it asks for, or chat when fact_question is false. Examples:" + examples)


def classify(url, line):
    schema = {"type": "object", "properties": {"fact_question": {"type": "boolean"},
              "ask": {"type": "string", "enum": ["chat"] + QUESTIONS}},
              "required": ["fact_question", "ask"], "additionalProperties": False}
    body = {"model": "default", "stream": False, "max_tokens": 24, "temperature": 0.0,
            "chat_template_kwargs": {"enable_thinking": False},
            "messages": [{"role": "system", "content": prompt()}, {"role": "user", "content": line}],
            "response_format": {"type": "json_schema", "json_schema": {"name": "turn", "schema": schema}}}
    req = urllib.request.Request(url + "/v1/chat/completions", json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    t0 = time.perf_counter()
    with urllib.request.urlopen(req, timeout=60) as r:
        raw = json.load(r)["choices"][0]["message"]["content"]
    ms = round((time.perf_counter() - t0) * 1000)
    try:
        out = json.loads(raw)
    except ValueError:
        return None, raw, ms
    ask = out.get("ask") if out.get("fact_question") and out.get("ask") in QUESTIONS else None
    low = line.lower()
    if ask and SELF.search(low) and not ADDRESSED.search(low):
        ask = None  # BotTurn.parse: the sender's own news asks the bot nothing
    return ask, raw, ms


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://127.0.0.1:30000")
    ap.add_argument("--set", choices=sorted(LINES), action="append")
    args = ap.parse_args()
    for name in args.set or sorted(LINES):
        right = wrong = missed = tagged = 0
        times = []
        for line, want in LINES[name]:
            got, raw, ms = classify(args.url, line)
            times.append(ms)
            if got == want or (got and want and frozenset((got, want)) in SAME):
                right += 1
                continue
            if want is None:
                tagged += 1
                kind = "chat tagged"
            elif got is None:
                missed += 1
                kind = "missed (normal reply)"
            else:
                wrong += 1
                kind = "wrong report"
            print(f"  {kind}: {line!r} want {want} got {got} ({raw})")
        times.sort()
        print(f"{name}: {right}/{len(LINES[name])} right, {wrong} wrong report, {missed} missed, "
              f"{tagged} chat tagged; p50 {times[len(times) // 2]} ms")


if __name__ == "__main__":
    main()
