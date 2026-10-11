#!/bin/sh
# Nav gate: fail when this branch gets a bot stuck on a move that works on the base (origin/main).
# Sweeps every map bots have visited through live physics (BotEdgeSweepCli), on the base and on the
# working tree, and compares. Both sweeps use this branch's sweep tool, so only the game code differs.
# The base sweep is cached per base commit and tool version, so a rerun sweeps only the branch.
# usage: tools/navgate/nav_gate.sh [base-ref]
set -eu
cd "$(dirname "$0")/../.."
REPO="$PWD"
BASE_REF="${1:-origin/main}"
OUT="$REPO/tmp/navgate"
mkdir -p "$OUT"
IMAGE=maven:3.9.6-amazoncorretto-21
EXPLOG="$HOME/Library/Application Support/cosmic-explog/exp-log.csv"

# Maps bots have visited, from the EXP log, plus any listed in tools/navgate/maps.txt.
MAPS="$OUT/maps.txt"
{ [ -f "$EXPLOG" ] && awk -F, 'NR>1{print $6}' "$EXPLOG"; [ -f tools/navgate/maps.txt ] && grep -v '^#' tools/navgate/maps.txt; } \
  | grep -E '^[0-9]+$' | sort -u > "$MAPS"
echo "maps: $(wc -l < "$MAPS" | tr -d ' ')"

mvn_in() { # $1 = project dir relative to the repo, then maven args
  dir="$1"; shift
  docker run --rm -v "$REPO":/src -w "/src/$dir" "$IMAGE" \
    mvn -o -q -Dmaven.repo.local=/src/.m2/repository "$@" > "$OUT/mvn.log" 2>&1 \
    || { tail -20 "$OUT/mvn.log"; echo "build failed in $dir"; exit 2; }
}
sweep_in() { # $1 = project dir relative to the repo, $2 = output tsv relative to the repo, then sweep args
  dir="$1"; tsv="$2"; shift 2
  docker run --rm -v "$REPO":/src -w /src "$IMAGE" java -Xmx4g \
    -cp "tmp/navgate/tool-classes:$dir/target/classes:$dir/target/test-classes:$(cat "$OUT/cp.txt")" \
    -Dwz-path=/src/wz server.bots.BotEdgeSweepCli "$tsv" "$@" > "$OUT/sweep.log" 2>&1 \
    || { tail -5 "$OUT/sweep.log"; echo "sweep failed in $dir"; exit 2; }
  grep '^TOTAL' "$OUT/sweep.log" || true
}

# Dependency classpath, as paths inside the container.
[ -s "$OUT/cp.txt" ] || mvn_in . dependency:build-classpath -Dmdep.outputFile=/src/tmp/navgate/cp.txt

# This branch, and its sweep tool, which both sweeps run.
mvn_in . test-compile
rm -rf "$OUT/tool-classes" && mkdir -p "$OUT/tool-classes/server/bots"
cp target/test-classes/server/bots/BotEdgeSweepCli*.class target/test-classes/server/bots/BotMovementSimulationLab*.class \
  "$OUT/tool-classes/server/bots/"
TOOL=$(cat src/test/java/server/bots/BotEdgeSweepCli.java src/test/java/server/bots/BotMovementSimulationLab.java \
  | shasum | cut -c1-8)

# Base: one worktree and one sweep per commit and tool version.
BASE_SHA=$(git rev-parse --short "$BASE_REF")
BASE_TSV="tmp/navgate/base-$BASE_SHA-$TOOL.tsv"
WT="tmp/navgate/wt-$BASE_SHA"
[ -d "$WT" ] || git worktree add -q --detach "$WT" "$BASE_REF"
[ -d "$WT/target/classes" ] || mvn_in "$WT" test-compile
if [ ! -s "$BASE_TSV" ]; then
  echo "sweeping $BASE_REF ($BASE_SHA)"
  sweep_in "$WT" "$BASE_TSV" @tmp/navgate/maps.txt
fi

echo "sweeping working tree"
HEAD_TSV="tmp/navgate/head.tsv"
sweep_in . "$HEAD_TSV" @tmp/navgate/maps.txt
python3 tools/navgate/compare_sweeps.py "$BASE_TSV" "$HEAD_TSV" "$OUT/candidates.txt"

# Confirm each candidate with 10 runs on each side; a single sweep can't tell a regression from a flaky move.
if [ ! -s "$OUT/candidates.txt" ]; then
  echo "NAV GATE: PASS"
  exit 0
fi
cut -f1 "$OUT/candidates.txt" | sort -u > "$OUT/candidate-maps.txt"
echo "confirming $(wc -l < "$OUT/candidates.txt" | tr -d ' ') candidates"
sweep_in "$WT" tmp/navgate/confirm-base.tsv --attempts 10 --only tmp/navgate/candidates.txt @tmp/navgate/candidate-maps.txt
sweep_in . tmp/navgate/confirm-head.tsv --attempts 10 --only tmp/navgate/candidates.txt @tmp/navgate/candidate-maps.txt
python3 tools/navgate/compare_sweeps.py --confirm "$OUT/confirm-base.tsv" "$OUT/confirm-head.tsv"
