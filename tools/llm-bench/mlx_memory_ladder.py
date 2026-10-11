#!/usr/bin/env python3
"""Drive a running SGLang (MLX) server through phases and mark them, for memlog alignment.

Phases: idle, 30 sequential requests, then batches at each LADDER concurrency (default 2:16,4:16,8:24,
as concurrency:count), then idle.
Each phase start/end goes to <out>/phases.jsonl with the scheduler's physical footprint. A guard
thread kills the server if the footprint passes GUARD_GB (default 10).
Usage: mlx_memory_ladder.py <scheduler_pid> <out_dir>
"""
import json
import os
import subprocess
import sys
import threading
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

URL = "http://127.0.0.1:30000/v1/chat/completions"
GUARD_GB = float(os.environ.get("GUARD_GB", "10"))


def footprint_gb(pid):
    out = subprocess.run(["vmmap", "--summary", str(pid)], capture_output=True, text=True).stdout
    for line in out.splitlines():
        if line.startswith("Physical footprint:"):
            v = line.split()[2]
            return float(v[:-1]) * (1 if v.endswith("G") else 1 / 1024 if v.endswith("M") else 1 / 1024 ** 2)
    return float("nan")


def ask(i, max_tokens=32):
    text = f"bot {i % 50}: say hi to a player in maplestory henesys in one short line"
    body = {"model": "default", "messages": [{"role": "user", "content": text}], "max_tokens": max_tokens,
            "temperature": 0, "chat_template_kwargs": {"enable_thinking": False}}
    req = urllib.request.Request(URL, json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.load(r)["usage"]["completion_tokens"]


def main():
    pid, out = int(sys.argv[1]), sys.argv[2]
    os.makedirs(out, exist_ok=True)
    marks = open(os.path.join(out, "phases.jsonl"), "a")
    stop = threading.Event()

    def mark(phase, edge):
        rec = {"t": round(time.time(), 2), "phase": phase, "edge": edge, "footprint_gb": round(footprint_gb(pid), 2)}
        marks.write(json.dumps(rec) + "\n"); marks.flush(); print(json.dumps(rec), flush=True)

    def guard():
        while not stop.wait(2):
            gb = footprint_gb(pid)
            if gb > GUARD_GB:
                print(f"GUARD tripped at {gb:.1f} GB", flush=True)
                subprocess.run(["pkill", "-9", "-f", "sglang::"]); subprocess.run(["pkill", "-9", "-f", "sglang.launch_server"])
                os._exit(2)

    threading.Thread(target=guard, daemon=True).start()
    n = 0
    mark("idle", "start"); time.sleep(8); mark("idle", "end")
    mark("seq30", "start")
    for _ in range(30):
        ask(n); n += 1
    mark("seq30", "end")
    ladder = os.environ.get("LADDER", "2:16,4:16,8:24")
    for conc, count in (tuple(map(int, step.split(":"))) for step in ladder.split(",")):
        mark(f"c{conc}", "start")
        with ThreadPoolExecutor(conc) as ex:
            list(ex.map(ask, range(n, n + count)))
        n += count
        mark(f"c{conc}", "end")
    mark("idle_after", "start"); time.sleep(10); mark("idle_after", "end")
    stop.set()


if __name__ == "__main__":
    main()
