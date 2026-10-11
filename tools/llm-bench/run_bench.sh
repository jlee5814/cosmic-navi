#!/bin/sh
# Start SGLang (MLX backend) on localhost, watch the scheduler's physical memory footprint,
# and run bot_chat_bench.py once the server is healthy.
# Usage: run_bench.sh <outdir> [extra launch_server args...]
# Env: SGLANG_DIR (default ~/Projects/inference/sglang), VENV (default my-venv), any SGLANG_MLX_* knobs.
set -u
OUT="$1"; shift
SGLANG_DIR="${SGLANG_DIR:-$HOME/Projects/inference/sglang}"
VENV="${VENV:-my-venv}"
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"

cd "$SGLANG_DIR" || exit 1
HF_HUB_OFFLINE=1 SGLANG_USE_MLX=1 "$VENV/bin/python" -m sglang.launch_server \
  --model-path mlx-community/Qwen3-0.6B-4bit --disable-cuda-graph \
  --host 127.0.0.1 --port 30000 --mem-fraction-static 0.25 --enable-cache-report "$@" \
  > "$OUT/server.log" 2>&1 &
echo $! > "$OUT/server.pid"

until curl -s -o /dev/null --max-time 2 http://127.0.0.1:30000/health; do sleep 2; done
SCHED=$(pgrep -f 'sglang::scheduler' | head -1)
echo "scheduler pid $SCHED" > "$OUT/memory.log"
( while kill -0 "$SCHED" 2>/dev/null; do
    printf '%s %s\n' "$(date +%H:%M:%S)" \
      "$(vmmap --summary "$SCHED" 2>/dev/null | awk '/Physical footprint:/{print $3; exit}')" >> "$OUT/memory.log"
    sleep 3
  done ) &
echo $! > "$OUT/monitor.pid"

"$VENV/bin/python" "$HERE/bot_chat_bench.py" --bots "$HERE/../../tmp/sglang/bots.json" \
  --out "$OUT/results.json" > "$OUT/bench.log" 2>&1
echo "BENCH_EXIT=$?" >> "$OUT/bench.log"
