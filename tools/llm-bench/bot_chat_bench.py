#!/usr/bin/env python3
"""Load test an OpenAI compatible LLM server (SGLang) with bot shaped chat traffic.

Builds prompts the way server.bots.llm.PromptBuilder does, from a snapshot of live
bots (/api/botdebug), and compares two prompt layouts:

  current     system prompt starts with the bot's own IGN, level and job, then
              rules shared by every bot (today's PromptBuilder.buildSystem)
  shared_first  the shared rules come first; the bot's identity moves to the
              user turn, so every bot's prompt starts with the same tokens and
              the server's prefix cache (SGLang RadixAttention) can reuse them

Each wave sends one message to every bot at a given concurrency. Waves run cold
(cache flushed first) to isolate cross bot sharing; a final warm wave repeats the
top concurrency without flushing. Requires the server's --enable-cache-report.

Usage:
  python bot_chat_bench.py --bots bots.json --url http://127.0.0.1:30000 --out results.json
"""
import argparse
import asyncio
import json
import random
import statistics
import time

import aiohttp

RULES = ("Talk like mmo chatter: lowercase, 1 short sentence, abbreviate whenever possible, text only, "
         "use internet slang when applicable, no emoji, no speaker label. "
         "Keep replies simple. Prefer fragments over full grammar. "
         "Answer only the latest message directly. If unsure, say so briefly instead of guessing. "
         "Never say you are an ai, assistant, chatbot, or bot. ")
OWNER = "Sipsaeki"
RELATION = (f"You are talking to your close gaming friend and party leader {OWNER}. "
            "Be friendly and helpful.")
QUESTIONS = ["how's grinding going", "where are you rn", "need pots?", "what lvl are you now",
             "found anything good", "wanna party later", "how much exp you got", "you good on mesos",
             "what mob you farming", "any drops yet", "you tired yet lol", "whats your next goal"]
HISTORY = [("yo you there", "ya just grinding"), ("need anything", "nah im good for now")]


def identity(bot):
    job = (bot.get("plannedJob") or "adventurer").lower().replace("_", " ")
    return bot["n"], bot.get("lvl", 1), job


def situation(bot):
    lvl = bot.get("lvl", 1)
    status = bot.get("status", "").split(";")[-1].strip()
    return (f"[Where you are now]\nMap: {bot.get('map', 0)}\nStatus: {status or 'grinding'}\n"
            f"Level {lvl}, {random.Random(bot['id']).randint(1, 99)}% to next\n")


def history(name):
    lines = ["Recent chat (older lines matter less):"]
    for i, (msg, reply) in enumerate(HISTORY):
        lines.append(f"[{(len(HISTORY) - i) * 3}m ago] {OWNER}: {msg}")
        lines.append(f"{name}: {reply}")
    return "\n".join(lines) + "\n\n"


def messages(bot, question, layout):
    name, lvl, job = identity(bot)
    tail = (situation(bot) + history(name)
            + "Reply to the newest message only. Treat older chat as background, not the topic.\n"
            + f"{OWNER}: {name} {question}\n{name}:")
    who = f"Your IGN is {name}. You are a real human MapleStory player, level {lvl} {job}. "
    if layout == "current":
        return [{"role": "system", "content": who + RULES + RELATION},
                {"role": "user", "content": tail}]
    return [{"role": "system", "content": "You are a real human MapleStory player. " + RULES + RELATION},
            {"role": "user", "content": who + "\n" + tail}]


async def one(session, url, msgs):
    body = {"model": "default", "messages": msgs, "max_tokens": 24, "temperature": 1.0, "top_p": 0.95,
            "top_k": 20, "presence_penalty": 1.5, "stream": True,
            "stream_options": {"include_usage": True},
            "chat_template_kwargs": {"enable_thinking": False}}
    t0 = time.perf_counter()
    ttft, usage, text = None, None, []
    async with session.post(f"{url}/v1/chat/completions", json=body) as resp:
        resp.raise_for_status()
        async for raw in resp.content:
            line = raw.decode().strip()
            if not line.startswith("data:") or line == "data: [DONE]":
                continue
            chunk = json.loads(line[5:])
            for ch in chunk.get("choices") or []:
                delta = (ch.get("delta") or {}).get("content")
                if delta:
                    if ttft is None:
                        ttft = time.perf_counter() - t0
                    text.append(delta)
            if chunk.get("usage"):
                usage = chunk["usage"]
    total = time.perf_counter() - t0
    cached = ((usage or {}).get("prompt_tokens_details") or {}).get("cached_tokens") or 0
    return {"latency": total, "ttft": ttft if ttft is not None else total,
            "prompt": (usage or {}).get("prompt_tokens", 0), "cached": cached,
            "output": (usage or {}).get("completion_tokens", 0), "text": "".join(text)}


async def wave(url, bots, layout, conc, wave_id):
    rng = random.Random(wave_id)
    sem = asyncio.Semaphore(conc)
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=300)) as session:
        async def run(bot):
            async with sem:
                return await one(session, url, messages(bot, rng.choice(QUESTIONS), layout))
        t0 = time.perf_counter()
        results = await asyncio.gather(*(run(b) for b in bots))
        wall = time.perf_counter() - t0
    lat = sorted(r["latency"] for r in results)
    ttft = sorted(r["ttft"] for r in results)
    p = lambda xs, q: xs[min(len(xs) - 1, int(q * len(xs)))]
    prompt = sum(r["prompt"] for r in results)
    return {"layout": layout, "concurrency": conc, "requests": len(results), "wall_s": round(wall, 2),
            "req_per_s": round(len(results) / wall, 2),
            "output_tok_per_s": round(sum(r["output"] for r in results) / wall, 1),
            "latency_p50_ms": round(1000 * statistics.median(lat)), "latency_p95_ms": round(1000 * p(lat, 0.95)),
            "ttft_p50_ms": round(1000 * statistics.median(ttft)), "ttft_p95_ms": round(1000 * p(ttft, 0.95)),
            "prompt_tokens": prompt, "cached_tokens": sum(r["cached"] for r in results),
            "cache_hit_pct": round(100 * sum(r["cached"] for r in results) / max(1, prompt), 1),
            "sample_replies": [r["text"] for r in results[:3]]}


async def flush(url):
    async with aiohttp.ClientSession() as s:
        async with s.post(f"{url}/flush_cache") as r:
            await r.text()


async def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--bots", required=True)
    ap.add_argument("--url", default="http://127.0.0.1:30000")
    ap.add_argument("--out", default="results.json")
    ap.add_argument("--concurrency", default="1,8,16,50")
    ap.add_argument("--layouts", default="current,shared_first")
    ap.add_argument("--no-warm", action="store_true", help="skip the warm repeat wave per layout")
    args = ap.parse_args()
    bots = json.load(open(args.bots))["bots"]
    levels = [int(c) for c in args.concurrency.split(",")]

    await one_warmup(args.url, bots)
    rows = []
    for layout in args.layouts.split(","):
        plan = [(conc, "cold", i) for i, conc in enumerate(levels)]
        if not args.no_warm:
            plan.append((levels[-1], "warm repeat", 99))
        for conc, mode, wave_id in plan:
            if mode == "cold":
                await flush(args.url)
                await asyncio.sleep(0.5)
            try:
                row = await wave(args.url, bots, layout, conc, wave_id=wave_id)
            except Exception as e:  # server stopped (e.g. memory guard): record it and stop this run
                row = {"layout": layout, "concurrency": conc, "error": type(e).__name__}
            row["mode"] = mode
            rows.append(row)
            print(json.dumps({k: v for k, v in row.items() if k != "sample_replies"}), flush=True)
            if "error" in row:
                break
    json.dump({"bots": len(bots), "rows": rows}, open(args.out, "w"), indent=2)


async def one_warmup(url, bots):
    """Compile and warm the model path so the first measured wave isn't paying for it."""
    async with aiohttp.ClientSession() as s:
        for layout in ("current", "shared_first"):
            await one(s, url, messages(bots[0], "hi", layout))
    await flush(url)


if __name__ == "__main__":
    asyncio.run(main())
