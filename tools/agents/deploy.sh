#!/bin/zsh
# The one way to deploy the live server (tools/agents/README.md). Takes the deploy lock, refuses while an agent
# watches bots (a watch claim), records the bots online, merges origin/main, rebuilds and restarts the server,
# brings the same bots back (the population scheduler doesn't), and writes the deploy to the journal.
#
# Usage, on the Mac from the repo root: tools/agents/deploy.sh --session NAME [--force]
#   --force   deploy even while a watch claim is live (it still names them in the journal)
set -euo pipefail
cd "$(dirname "$0")/../.."
SESSION="" FORCE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --session) SESSION="$2"; shift 2 ;;
    --force) FORCE=1; shift ;;
    *) echo "unknown argument $1"; exit 2 ;;
  esac
done
[ -n "$SESSION" ] || { echo "need --session NAME"; exit 2; }
API=http://localhost:8089
AG="python3 tools/agents/agents.py"

$AG lock deploy --session "$SESSION" --minutes 20 || { echo "another deploy is running; try again later"; exit 1; }
trap '$AG unlock deploy --session "$SESSION" >/dev/null' EXIT

WATCHES=$($AG list | grep ' watch by ' | grep -v " by $SESSION " || true)
if [ -n "$WATCHES" ] && [ $FORCE -eq 0 ]; then
  echo "agents are watching bots; a restart would cut them off:"; echo "$WATCHES"
  echo "ask them, wait for the claims to end, or pass --force"
  exit 1
fi

ONLINE=$(curl -sf "$API/api/live" | python3 -c "
import json, sys
d = json.load(sys.stdin)
print(','.join(str(i) for i in sorted(b['id'] for m in d['maps'].values() for b in m['bots'])))" || echo "")
BEFORE=$(git rev-parse --short HEAD)
git fetch -q origin
git merge -q --no-edit origin/main
AFTER=$(git rev-parse --short HEAD)
COMMITS=$(git log --format='%s' "$BEFORE..$AFTER" | grep -o '(#[0-9]*)' | tr -d '()' | tr '\n' ' ' || true)
docker compose -p cosmic-local -f compose.localonly.yaml up -d --build maplestory

for i in $(seq 1 60); do
  curl -sf -o /dev/null "$API/api/live" && break
  sleep 5
done
sleep 15  # the boot roster restore runs first; spawnbot skips bots it already brought back
if [ -n "$ONLINE" ]; then
  RESULT=$(curl -sf "$API/api/spawnbot?id=$ONLINE" | python3 -c "
import json, sys
r = json.load(sys.stdin)['results']
print(sum(x['spawned'] for x in r), 'respawned,', sum(not x['spawned'] for x in r), 'already online')" || echo "respawn failed")
else
  RESULT="no bots were online"
fi
MSG="deployed $BEFORE..$AFTER ${COMMITS:-(no new PRs)}; bots $RESULT"
[ -n "$WATCHES" ] && MSG="$MSG; forced past watches: $(echo "$WATCHES" | cut -d' ' -f1 | tr '\n' ' ')"
$AG note --session "$SESSION" "$MSG"
echo "$MSG"
