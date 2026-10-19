#!/bin/bash
# Retrain the learned grind target scorer and promote it only when it earns it (docs/bot/learned-grind-target.md).
# Trains trees on the whole decision log, gates against the rule on held out time, compares with the served
# model (the champion), and swaps the served file atomically on a pass. The server picks it up within a minute.
#
# Usage, from the repo root: tools/learned/retrain.sh [model]   (default trees)
set -euo pipefail
cd "$(dirname "$0")/../.."
KIND="${1:-trees}"
SERVED=models/grind-target-model.json
OUT="tools/learned/out/grind-target-$KIND.json"
mkdir -p models tools/learned/out
python3 tools/learned/train_grind_target.py logs/bot-decisions --model "$KIND" --champion "$SERVED" --out "$OUT"
if python3 -c "import json,sys; sys.exit(0 if json.load(open('$OUT'))['promote'] else 1)"; then
  cp "$OUT" "$SERVED.tmp" && mv "$SERVED.tmp" "$SERVED"
  echo "promoted to $SERVED"
else
  echo "kept the served model"
fi
