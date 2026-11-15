#!/bin/sh
# Build everything into $OUT (default ./build):
#   golden/       the reference model (Rust, Rivet) as a static library and a CLI
#   generated/    SystemVerilog from Chisel, one folder per configuration
#   sim-ooo, sim-inorder   Verilator simulators of the two configurations
#   isa/          the official ISA tests, firmware/  the benchmarks
#   OUT=/tmp/silicon sh tools/build.sh [ooo,inorder]
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=${OUT:-$ROOT/build}
CONFIGS=${1:-ooo,inorder}
mkdir -p "$OUT"

echo "== reference model (Rust + Rivet)"
(cd "$ROOT/golden" && CARGO_TARGET_DIR="$OUT/golden" cargo build --release --quiet)

echo "== Chisel -> SystemVerilog"
(cd "$ROOT/rtl" && sbt "runMain silicon.Main $OUT/generated $CONFIGS" < /dev/null | grep -E "wrote|error|Error" )

for cfg in $(echo "$CONFIGS" | tr ',' ' '); do
  echo "== Verilator: $cfg"
  rm -rf "$OUT/obj-$cfg"
  verilator --cc --exe --build -j 0 -O3 --x-assign fast --x-initial fast -Wno-fatal -Wno-lint -Wno-style \
    --top-module Core -Mdir "$OUT/obj-$cfg" -CFLAGS "-O2" \
    "$OUT/generated/$cfg/Core.sv" "$ROOT/sim/main.cpp" "$OUT/golden/release/libgolden.a" \
    -LDFLAGS "-lpthread -ldl -lm" > "$OUT/verilator-$cfg.log" 2>&1 || { tail -40 "$OUT/verilator-$cfg.log"; exit 1; }
  cp "$OUT/obj-$cfg/VCore" "$OUT/sim-$cfg"
done

echo "== tests and firmware"
OUT="$OUT" sh "$ROOT/tests/build-isa.sh"
if [ -f "$ROOT/firmware/build.sh" ]; then OUT="$OUT" sh "$ROOT/firmware/build.sh"; fi
