#!/bin/sh
# The whole evaluation: build the four configurations, run the co-simulation
# tests, measure IPC (with a memory-latency sweep), synthesize, and write
# results/README.md.
#   OUT=/tmp/silicon sh eval/run_all.sh
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=${OUT:-$ROOT/build}
export OUT
sh "$ROOT/tools/build.sh" ooo,inorder,nopred,small
mkdir -p "$ROOT/results"

echo "== co-simulation tests"
SILICON_BUILD="$OUT" SILICON_RANDOM=${RANDOM_PROGRAMS:-100} python3 -m pytest "$ROOT/tests" -q | tail -3

echo "== IPC"
python3 "$ROOT/eval/ipc.py" "$OUT" --out "$ROOT/results/ipc.json"

echo "== synthesis"
for c in ooo inorder small; do
  python3 "$ROOT/eval/synth.py" "$OUT/generated" "$c" --out "$ROOT/results/synth-$c.json"
done

python3 "$ROOT/eval/report.py" "$ROOT/results" > "$ROOT/results/README.md"
echo "wrote results/README.md"
