#!/bin/sh
# Fresh SGLang (MLX) server with the mlx_memlog hook, driven through mlx_memory_ladder.py.
# Usage: run_memory_ladder.sh <outdir>   (extra server env, e.g. SGLANG_MLX_CLEAR_CACHE_STEPS, passes through)
set -u
mkdir -p "$1"; OUT="$(cd "$1" && pwd)"
SGLANG_DIR="${SGLANG_DIR:-$HOME/Projects/inference/sglang}"
VENV="${VENV:-my-venv}"
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"
(cd "$SGLANG_DIR" && PYTHONPATH="$HERE/mlx_memlog" MLX_MEMLOG_DIR="$OUT" HF_HUB_OFFLINE=1 SGLANG_USE_MLX=1 \
  "$VENV/bin/python" -m sglang.launch_server --model-path mlx-community/Qwen3-0.6B-4bit --disable-cuda-graph \
  --host 127.0.0.1 --port 30000 --mem-fraction-static "${MEM_FRACTION:-0.25}" --enable-cache-report > "$OUT/server.log" 2>&1) &
until curl -s -o /dev/null --max-time 2 http://127.0.0.1:30000/health; do sleep 2; done
SCHED=$(pgrep -f 'sglang::scheduler' | head -1)
echo "scheduler $SCHED" > "$OUT/driver.log"
"$SGLANG_DIR/$VENV/bin/python" "$HERE/mlx_memory_ladder.py" "$SCHED" "$OUT" >> "$OUT/driver.log" 2>&1
pkill -f 'sglang.launch_server'; sleep 3; pkill -9 -f 'sglang::' 2>/dev/null; pkill -9 -f 'sglang.launch_server' 2>/dev/null
echo LADDER_DONE >> "$OUT/driver.log"
