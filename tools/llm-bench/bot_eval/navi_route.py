"""Test 5: does the Navi routing call (server.bots.llm.NaviRouter) pick the tool an owner's unmatched navi
whisper asks for, with the right bot, and route chat and uncovered requests to none? Scored by label.

Only requests the command patterns miss reach the model, so every line here is one they miss. The tool
list and prompt examples are read from the Java source, so they cannot drift; the prompt sentences around
them are copied from NaviRouter.prompt and must change when it does.

    python3 navi_route.py                 # both line sets, needs the chat server on :30000
    python3 navi_route.py --set held_out  # only lines unlike the prompt's examples
"""
import argparse
import json
import re
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ROUTER = (ROOT / "src/main/java/server/bots/llm/NaviRouter.java").read_text()
_tools = ROUTER[ROUTER.index("TOOLS = {"):ROUTER.index("};", ROUTER.index("TOOLS = {"))]
TOOLS = re.findall(r'\{"(\w+)", "([^"]+)"\}', _tools)
NAMES = [t for t, _ in TOOLS]
_examples = ROUTER[ROUTER.index("EXAMPLES = {"):ROUTER.index("};", ROUTER.index("EXAMPLES = {"))]
EXAMPLES = re.findall(r'\{"([^"]+)", "(\w+)", "([^"]*)"\}', _examples)
NEEDS_BOT = {"why", "rescue", "park"}
CHANGES_STATE = {"rescue", "park"}

# (request after "navi", tool, bot). None matches a command pattern, so the model sees every one.
LINES = {
    "tuning": [
        ("did anyone email me", "email", ""), ("check gmail", "email", ""), ("got any mail for me", "email", ""),
        ("how are my guys doing", "fleet", ""), ("bots ok", "fleet", ""),
        ("what is 26 doing", "why", "26"), ("why is SipsBuddy3 just standing there", "why", "SipsBuddy3"),
        ("12 is stuck on a rope", "rescue", "12"), ("get SipsBuddy9 out of there", "rescue", "SipsBuddy9"),
        ("log off 4", "park", "4"), ("how slow is the model rn", "llm", ""),
        ("reply to my boss", "none", ""), ("lol nice", "none", ""), ("whats the time", "none", ""),
        ("buy me 100 pots", "none", ""),
    ],
    "held_out": [
        ("anything in my inbox", "email", ""), ("new messages", "email", ""),
        ("did my landlord write back", "email", ""),
        ("status of everyone", "fleet", ""), ("are the bots grinding", "fleet", ""),
        ("whats up with 31", "why", "31"), ("where is SipsBuddy14 and what is it doing", "why", "SipsBuddy14"),
        ("unstuck 8 please", "rescue", "8"), ("help 20 hes trapped", "rescue", "20"),
        ("put SipsBuddy2 to sleep", "park", "SipsBuddy2"), ("take 17 offline", "park", "17"),
        ("is the ai fast today", "llm", ""), ("model latency", "llm", ""),
        ("delete my emails", "none", ""), ("send an email to vera", "none", ""), ("good night", "none", ""),
        ("rescue the princess", "none", ""), ("whats the weather", "none", ""),
    ],
}


def prompt():
    tools = "".join(f" {t}: {d}." for t, d in TOOLS)
    examples = "".join(f" {r} -> {t}" + (f" {b}" if b else "") + "." for r, t, b in EXAMPLES)
    return ("A player in an online game whispered the message below to Navi, their own assistant. "
            "Pick the one tool it asks for." + tools
            + " bot: the bot name or number the message names, for why, rescue and park; empty otherwise."
            + " Examples:" + examples)


def bot_of(raw):
    """NaviRouter.parse: the last word, punctuation dropped."""
    words = re.sub(r"[^A-Za-z0-9\s]", " ", raw or "").split()
    return words[-1] if words else ""


def route(url, line):
    schema = {"type": "object", "properties": {"tool": {"type": "string", "enum": NAMES},
              "bot": {"type": "string"}}, "required": ["tool", "bot"], "additionalProperties": False}
    body = {"model": "default", "stream": False, "max_tokens": 32, "temperature": 0.0,
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
        return None, "", raw, ms
    tool = str(out.get("tool", "")).strip().lower()
    if tool not in NAMES:
        return None, "", raw, ms
    return tool, bot_of(out.get("bot")) if tool in NEEDS_BOT else "", raw, ms


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://127.0.0.1:30000")
    ap.add_argument("--set", choices=sorted(LINES), action="append")
    args = ap.parse_args()
    shown = {r.lower() for r, _, _ in EXAMPLES}
    for name in args.set or sorted(LINES):
        right = wrong_tool = wrong_bot = missed = false_route = state_unasked = 0
        times = []
        for line, want, want_bot in LINES[name]:
            assert line.lower() not in shown, f"{line!r} is a prompt example"
            got, bot, raw, ms = route(args.url, line)
            times.append(ms)
            if got == want and bot.lower() == want_bot.lower():
                right += 1
                continue
            if got in CHANGES_STATE and want not in CHANGES_STATE:
                state_unasked += 1  # still asks for navi yes first, so nothing changes unasked
            if got == want:
                wrong_bot += 1
                kind = "wrong bot"
            elif want == "none":
                false_route += 1
                kind = "routed chat"
            elif got in (None, "none"):
                missed += 1
                kind = "missed (help line)"
            else:
                wrong_tool += 1
                kind = "wrong tool"
            print(f"  {kind}: {line!r} want {want} {want_bot} got {got} {bot} ({raw})")
        times.sort()
        print(f"{name}: {right}/{len(LINES[name])} right, {wrong_tool} wrong tool, {wrong_bot} wrong bot, "
              f"{missed} missed, {false_route} routed chat, {state_unasked} rescue or park unasked; "
              f"p50 {times[len(times) // 2]} ms, max {times[-1]} ms")


if __name__ == "__main__":
    main()
