#!/bin/sh
# One fresh SGLang server per (layout, concurrency) wave, with a memory guard that stops the
# server if the scheduler's physical footprint passes GUARD_GB. Results: <outdir>/wave-*.json
# and <outdir>/memory.log, plus MLX counters per wave in <outdir>/memlog-<wave>/ (mlx_memlog hook;
# MLX_PATCH_* env knobs pass through to the server). Usage: run_matrix.sh <outdir>
set -u
mkdir -p "$1"; OUT="$(cd "$1" && pwd)"
SGLANG_DIR="${SGLANG_DIR:-$HOME/Projects/inference/sglang}"
VENV="${VENV:-my-venv}"
GUARD_GB="${GUARD_GB:-10}"
HERE="$(cd "$(dirname "$0")" && pwd)"
BOTS="$HERE/../../tmp/sglang/bots.json"
mkdir -p "$OUT"
: > "$OUT/memory.log"

footprint_gb() {
  vmmap --summary "$1" 2>/dev/null | awk '/Physical footprint:/{v=$3; u=substr(v,length(v)); n=substr(v,1,length(v)-1);
    if (u=="G") print n; else if (u=="M") print n/1024; else print 0; exit}'
}

for LAYOUT in current shared_first; do
  for CONC in 1 8 16 50; do
    TAG="$LAYOUT-c$CONC"
    (cd "$SGLANG_DIR" && PYTHONPATH="$HERE/mlx_memlog" MLX_MEMLOG_DIR="$OUT/memlog-$TAG" HF_HUB_OFFLINE=1 SGLANG_USE_MLX=1 "$VENV/bin/python" -m sglang.launch_server \
      --model-path mlx-community/Qwen3-0.6B-4bit --disable-cuda-graph --host 127.0.0.1 --port 30000 \
      --mem-fraction-static "${MEM_FRACTION:-0.25}" --enable-cache-report > "$OUT/server-$TAG.log" 2>&1) &
    until curl -s -o /dev/null --max-time 2 http://127.0.0.1:30000/health; do sleep 2; done
    SCHED=$(pgrep -f 'sglang::scheduler' | head -1)

    ( peak=0
      while kill -0 "$SCHED" 2>/dev/null; do
        gb=$(footprint_gb "$SCHED"); [ -n "$gb" ] || gb=0
        echo "$(date +%H:%M:%S) $TAG ${gb}G" >> "$OUT/memory.log"
        if awk "BEGIN{exit !($gb > $GUARD_GB)}"; then
          echo "$(date +%H:%M:%S) $TAG GUARD tripped at ${gb}G" >> "$OUT/memory.log"
          # Precise patterns only: this script's own path and arguments also contain "sglang".
          pkill -9 -f 'sglang::'; pkill -9 -f 'sglang.launch_server'; break
        fi
        sleep 2
      done ) &
    GUARD=$!

    "$SGLANG_DIR/$VENV/bin/python" "$HERE/bot_chat_bench.py" --bots "$BOTS" --layouts "$LAYOUT" \
      --concurrency "$CONC" --no-warm --out "$OUT/wave-$TAG.json" > "$OUT/bench-$TAG.log" 2>&1

    pkill -f 'sglang.launch_server'; sleep 3; pkill -9 -f 'sglang::' 2>/dev/null; pkill -9 -f 'sglang.launch_server' 2>/dev/null
    kill "$GUARD" 2>/dev/null
    sleep 2
  done
done
echo MATRIX_DONE >> "$OUT/memory.log"
