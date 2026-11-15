#!/bin/sh
# Build the official rv64ui and rv64um tests with Silicon's machine-mode
# environment into $OUT/isa/*.elf.
#   OUT=build sh tests/build-isa.sh
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=${OUT:-$ROOT/build}
RT=$ROOT/third_party/riscv-tests
[ -d "$RT/isa" ] || sh "$ROOT/tools/fetch-riscv-tests.sh"
mkdir -p "$OUT/isa"
CC=${RISCV_CC:-riscv64-unknown-elf-gcc}
FLAGS="-march=rv64im_zicsr_zifencei -mabi=lp64 -static -mcmodel=medany -fvisibility=hidden -nostdlib -nostartfiles"

# ma_data needs misaligned loads and stores, which Silicon traps on (the
# official environment has no handler that emulates them), so it is left out.
UI="add addi addiw addw and andi auipc beq bge bgeu blt bltu bne simple fence_i jal jalr
lb lbu lh lhu lw lwu ld ld_st lui or ori sb sh sw sd st_ld sll slli slliw sllw slt slti
sltiu sltu sra srai sraiw sraw srl srli srliw srlw sub subw xor xori"
UM="div divu divuw divw mul mulh mulhsu mulhu mulw rem remu remuw remw"

n=0
for t in $UI; do
  $CC $FLAGS -I"$ROOT/tests/env" -I"$RT/env" -I"$RT/isa/macros/scalar" -T"$ROOT/tests/env/link.ld" \
    "$RT/isa/rv64ui/$t.S" -o "$OUT/isa/rv64ui-$t.elf"
  n=$((n+1))
done
for t in $UM; do
  $CC $FLAGS -I"$ROOT/tests/env" -I"$RT/env" -I"$RT/isa/macros/scalar" -T"$ROOT/tests/env/link.ld" \
    "$RT/isa/rv64um/$t.S" -o "$OUT/isa/rv64um-$t.elf"
  n=$((n+1))
done
echo "built $n ISA tests into $OUT/isa"
