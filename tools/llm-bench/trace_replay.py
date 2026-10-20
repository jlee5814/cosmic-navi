#!/usr/bin/env python3
"""Replay live bot LLM traffic (cache/llm-stats/calls.tsv from the game server) against an OpenAI
compatible server, open loop: each call is sent at its recorded arrival time whether or not earlier calls
finished, so the server sees the burst pattern players produced, not a fixed concurrency.

A call's arrival is its completion stamp minus its latency (at_ms - latency_ms). Prompts carry the shared
rules bot_chat_bench sends, padded with varied filler words to the recorded prompt length; the output is
held to the recorded length with ignore_eos. Calls the server could not size (timeouts, errors) take the
median of the ones it did. Run it under run_matrix.sh (TRACE=...) for a fresh server, the memory guard and
the MLX counters, once stock and once patched.

What a replay can't reproduce: the original prompt text, so prefix cache hits here are not live hits (read
those from navi llm), and the JSON schema of structured calls, so their grammar cost is not paid.

Usage:
  python trace_replay.py --trace calls.tsv --url http://127.0.0.1:30000 --out replay.json
  python trace_replay.py --trace calls.tsv --busiest 10 --speed 2   # peak 10 minutes, twice as fast
"""
import argparse
import asyncio
import csv
import json
import random
import statistics
import time
from dataclasses import dataclass

WORDS = ("game map mob drop quest party level skill potion scroll town shop meso grind boss portal rope ladder "
         "ferry guild buddy trade item equip armor weapon glove shoe hat cape ring pet chair channel world "
         "henesys ellinia perion kerning sleepywood orbis ludibrium leafre slime mushroom stump pig snail").split()


@dataclass
class Call:
    offset_s: float      # arrival, seconds after the trace's first arrival
    kind: str
    prompt: int          # tokens to send
    output: int          # tokens to generate
    live_latency_ms: int


def load_trace(path, kinds=None):
    """Calls in arrival order. Unknown token counts (-1) take the median of known ones of the same kind,
    then of all calls, then a bot chat default."""
    with open(path, newline="") as f:
        rows = [r for r in csv.DictReader(f, delimiter="\t") if not kinds or r["kind"] in kinds]
    if not rows:
        return []

    def medians(field):
        known = [(r["kind"], int(r[field])) for r in rows if int(r[field]) > 0]
        by_kind = {}
        for k, v in known:
            by_kind.setdefault(k, []).append(v)
        overall = statistics.median([v for _, v in known]) if known else None
        return {k: statistics.median(v) for k, v in by_kind.items()}, overall

    prompt_med, prompt_all = medians("prompt_tokens")
    output_med, output_all = medians("completion_tokens")

    def size(r, field, by_kind, overall, default):
        v = int(r[field])
        if v > 0:
            return v
        return int(by_kind.get(r["kind"]) or overall or default)

    arrivals = []
    for r in rows:
        latency = max(0, int(r["latency_ms"]))
        arrivals.append((int(r["at_ms"]) - latency, r, latency))
    arrivals.sort(key=lambda a: a[0])
    t0 = arrivals[0][0]
    return [Call((at - t0) / 1000.0, r["kind"],
                 size(r, "prompt_tokens", prompt_med, prompt_all, 300),
                 size(r, "completion_tokens", output_med, output_all, 24), latency)
            for at, r, latency in arrivals]


def busiest(calls, minutes):
    """The window of the given length holding the most arrivals, rebased to start at zero."""
    span = minutes * 60.0
    best, best_i, j = 0, 0, 0
    for i, c in enumerate(calls):
        while calls[j].offset_s < c.offset_s - span:
            j += 1
        if i - j + 1 > best:
            best, best_i = i - j + 1, j
    picked = calls[best_i:best_i + best]
    start = picked[0].offset_s if picked else 0.0
    return [Call(c.offset_s - start, c.kind, c.prompt, c.output, c.live_latency_ms) for c in picked]


def peak_in_flight(intervals):
    """Most calls open at once, from (start_s, end_s) pairs."""
    events = sorted([(s, 1) for s, _ in intervals] + [(e, -1) for _, e in intervals], key=lambda x: (x[0], x[1]))
    peak = cur = 0
    for _, d in events:
        cur += d
        peak = max(peak, cur)
    return peak


def live_peak(calls):
    return peak_in_flight([(c.offset_s, c.offset_s + c.live_latency_ms / 1000.0) for c in calls])


def filler(n, seed):
    """n words, different per call, so the padding never lands in the prefix cache by accident."""
    rng = random.Random(seed)
    return " ".join(rng.choice(WORDS) for _ in range(max(0, n)))


def percentile(sorted_vals, p):
    """Nearest rank, as LlmStats.percentile."""
    if not sorted_vals:
        return None
    rank = -(-p * len(sorted_vals) // 100)
    return sorted_vals[min(len(sorted_vals), max(1, rank)) - 1]


def summarize(calls, results, wall_s, live_calls):
    ok = [r for r in results if "error" in r and r["error"] is None]
    errors = [r for r in results if r.get("error")]
    lat = sorted(round(1000 * r["latency"]) for r in ok)
    ttft = sorted(round(1000 * r["ttft"]) for r in ok)
    prompt = sum(r["prompt"] for r in ok)
    pct = lambda xs, p: percentile(xs, p)
    types = {}
    for r in errors:
        types[r["error"]] = types.get(r["error"], 0) + 1
    return {
        "requests": len(calls), "completed": len(ok), "errors": len(errors), "error_types": types,
        "first_error_s": round(min(r["sent_s"] for r in errors), 1) if errors else None,
        "wall_s": round(wall_s, 1), "trace_span_s": round(calls[-1].offset_s, 1) if calls else 0,
        "latency_ms": {"p50": pct(lat, 50), "p95": pct(lat, 95), "p99": pct(lat, 99), "max": lat[-1] if lat else None},
        "ttft_ms": {"p50": pct(ttft, 50), "p95": pct(ttft, 95), "p99": pct(ttft, 99)},
        "peak_in_flight": peak_in_flight([(r["sent_s"], r["sent_s"] + r["latency"]) for r in results if "latency" in r]),
        "live_peak_in_flight": live_peak(live_calls),
        "live_latency_ms": {"p50": pct(sorted(c.live_latency_ms for c in live_calls), 50),
                            "p99": pct(sorted(c.live_latency_ms for c in live_calls), 99)},
        "prompt_tokens_target_mean": round(statistics.mean(c.prompt for c in calls), 1) if calls else None,
        "prompt_tokens_sent_mean": round(prompt / len(ok), 1) if ok else None,
        "output_tokens": sum(r["output"] for r in ok),
        "output_tok_per_s": round(sum(r["output"] for r in ok) / wall_s, 1) if wall_s > 0 else None,
        "cache_hit_pct_replay": round(100 * sum(r["cached"] for r in ok) / max(1, prompt), 1),
    }


async def send(session, url, system, call, seed, base_tokens):
    body ={"model": "default", "stream": True, "stream_options": {"include_usage": True},
            "max_tokens": max(1, call.output), "ignore_eos": True, "temperature": 0.0,
            "chat_template_kwargs": {"enable_thinking": False},
            "messages": [{"role": "system", "content": system},
                         {"role": "user", "content": filler(call.prompt - base_tokens, seed)}]}
    t0 = time.perf_counter()
    ttft, usage = None, None
    async with session.post(f"{url}/v1/chat/completions", json=body) as resp:
        resp.raise_for_status()
        async for raw in resp.content:
            line = raw.decode().strip()
            if not line.startswith("data:") or line == "data: [DONE]":
                continue
            chunk = json.loads(line[5:])
            if ttft is None and any((ch.get("delta") or {}).get("content") for ch in chunk.get("choices") or []):
                ttft = time.perf_counter() - t0
            if chunk.get("usage"):
                usage = chunk["usage"]
    total = time.perf_counter() - t0
    usage = usage or {}
    return {"latency": total, "ttft": ttft if ttft is not None else total,
            "prompt": usage.get("prompt_tokens", 0), "output": usage.get("completion_tokens", 0),
            "cached": ((usage.get("prompt_tokens_details") or {}).get("cached_tokens") or 0)}


async def replay(url, calls, speed, system):
    import aiohttp
    results = []
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=300)) as session:
        # Calibrate: the system prompt plus one filler word, so filler length = target - base.
        warm = await send(session, url, system, Call(0, "warm", 1, 1, 0), seed=-1, base_tokens=0)
        base = max(0, warm["prompt"] - 1)
        t0 = time.perf_counter()

        async def one(i, call):
            delay = call.offset_s / speed - (time.perf_counter() - t0)
            if delay > 0:
                await asyncio.sleep(delay)
            sent = time.perf_counter() - t0
            try:
                r = await send(session, url, system, call, seed=i, base_tokens=base)
                r.update(sent_s=sent, error=None)
            except Exception as e:  # the server died (memory guard) or refused: keep going, count it
                r = {"sent_s": sent, "latency": time.perf_counter() - t0 - sent, "error": type(e).__name__}
            results.append(r)

        await asyncio.gather(*(one(i, c) for i, c in enumerate(calls)))
        wall = time.perf_counter() - t0
    return results, wall, base


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--trace", required=True, help="cache/llm-stats/calls.tsv from the game server")
    ap.add_argument("--url", default="http://127.0.0.1:30000")
    ap.add_argument("--out", default="replay.json")
    ap.add_argument("--kinds", help="comma separated kinds to keep (chat, structured, navi_route, generate)")
    ap.add_argument("--busiest", type=float, help="replay only the busiest window of this many minutes")
    ap.add_argument("--speed", type=float, default=1.0, help="2 replays twice as fast, so twice the load")
    args = ap.parse_args()

    calls = load_trace(args.trace, set(args.kinds.split(",")) if args.kinds else None)
    if not calls:
        raise SystemExit("no calls in the trace")
    if args.busiest:
        calls = busiest(calls, args.busiest)
    from bot_chat_bench import RELATION, RULES  # the shared prefix live bots send
    system = "You are a real human MapleStory player. " + RULES + RELATION

    results, wall, base = asyncio.run(replay(args.url, calls, args.speed, system))
    summary = summarize(calls, results, wall, calls)
    summary.update(trace=args.trace, busiest_min=args.busiest, speed=args.speed, system_prompt_tokens=base)
    print(json.dumps(summary), flush=True)
    json.dump(summary, open(args.out, "w"), indent=2)


if __name__ == "__main__":
    main()
