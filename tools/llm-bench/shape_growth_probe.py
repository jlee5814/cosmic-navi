#!/usr/bin/env python3
"""Does SGLang (MLX backend) memory grow with distinct prompt lengths or with request count?

Sends N identical prompts, then N prompts that each have a different token length, one at a
time, reading the scheduler's physical footprint (vmmap) before and after each phase.
Usage: shape_growth_probe.py <scheduler_pid> [N]
"""
import json
import subprocess
import sys
import urllib.request

URL = "http://127.0.0.1:30000/v1/chat/completions"


def footprint_gb(pid):
    out = subprocess.run(["vmmap", "--summary", str(pid)], capture_output=True, text=True).stdout
    for line in out.splitlines():
        if line.startswith("Physical footprint:"):
            v = line.split()[2]
            return float(v[:-1]) * (1 if v.endswith("G") else 1 / 1024 if v.endswith("M") else 1 / 1024 ** 2)
    return float("nan")


def ask(text):
    body = {"model": "default", "messages": [{"role": "user", "content": text}], "max_tokens": 8,
            "temperature": 0, "chat_template_kwargs": {"enable_thinking": False}}
    req = urllib.request.Request(URL, json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.load(r)["usage"]["prompt_tokens"]


def main():
    pid, n = int(sys.argv[1]), int(sys.argv[2]) if len(sys.argv) > 2 else 30
    base = "tell me a short fact about maplestory slimes"
    report = {"start_gb": footprint_gb(pid)}
    lengths = {ask(base) for _ in range(n)}
    report["after_identical_gb"] = footprint_gb(pid)
    report["identical_distinct_lengths"] = len(lengths)
    lengths = {ask(base + " please" * (i + 1)) for i in range(n)}
    report["after_varied_gb"] = footprint_gb(pid)
    report["varied_distinct_lengths"] = len(lengths)
    print(json.dumps(report))


if __name__ == "__main__":
    main()
