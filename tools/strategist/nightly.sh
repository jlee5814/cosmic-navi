#!/bin/zsh
# Nightly fleet strategist: snapshot the fleet, ask Sonnet (claude -p on the owner's Claude plan, read
# only tools) for plans and findings, and keep the report. Read only: nothing is sent to the game.
# Usage: tools/strategist/nightly.sh [model]   (default sonnet)
set -euo pipefail
cd "$(dirname "$0")"
MODEL=${1:-sonnet}
TS=$(date +%Y%m%d-%H%M)
mkdir -p runs/nightly
python3 fleet_snapshot.py "runs/nightly/snapshot-$TS.json"
python3 strategist.py "runs/nightly/snapshot-$TS.json" "runs/nightly/plan-$TS" --model "$MODEL" > /dev/null
# Stuck spots seen twice become GitHub issues (stuck-spot label); list what it did in the report.
{
  echo; echo "## Fix tasks for stuck spots"; echo
  python3 stuck_tracker.py "runs/nightly/snapshot-$TS.json" 2>&1 || echo "- the stuck tracker failed; see the line above"
} > "runs/nightly/plan-$TS/fix-tasks.md"
[ -s "runs/nightly/plan-$TS/fix-tasks.md" ] && cat "runs/nightly/plan-$TS/fix-tasks.md" >> "runs/nightly/plan-$TS/report.md"
# Rank training bots by the EXP lost on them, so the next fix is the costliest bug, not the latest one seen.
python3 lost_exp.py "runs/nightly/snapshot-$TS.json" --hours 24 >> "runs/nightly/plan-$TS/report.md" 2>&1 \
  || echo "- the lost EXP ranking failed" >> "runs/nightly/plan-$TS/report.md"
ln -sfn "plan-$TS" runs/nightly/latest
# Keep two weeks of runs.
python3 - <<'EOF'
import shutil
from pathlib import Path
runs = sorted(Path("runs/nightly").glob("plan-*"))
for d in runs[:-14]:
    shutil.rmtree(d)
    Path(f"runs/nightly/snapshot-{d.name[5:]}.json").unlink(missing_ok=True)
EOF
echo "$PWD/runs/nightly/plan-$TS/report.md"
