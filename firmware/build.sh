#!/bin/sh
# Build the benchmark programs into $OUT/fw/*.elf: nine kernels in C and two
# in Zig, each linked with the same start-up code and runtime. A program
# checks its own result against firmware/c/expected.txt (computed by a native
# build of the same C code, and by Python's hashlib for SHA-256).
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
FW=$ROOT/firmware
OUT=${OUT:-$ROOT/build}
mkdir -p "$OUT/fw/obj"
CC=${RISCV_CC:-riscv64-unknown-elf-gcc}
ZIG=${ZIG:-zig}
command -v "$ZIG" > /dev/null || ZIG=/opt/zig/zig
ARCH="-march=rv64im_zicsr -mabi=lp64 -mcmodel=medany"
CFLAGS="$ARCH -O2 -ffreestanding -fno-builtin -fno-tree-loop-distribute-patterns -I$FW/common"
LDFLAGS="$ARCH -nostdlib -nostartfiles -static -T$FW/common/link.ld -Wl,--no-warn-rwx-segments"

$CC $ARCH -c "$FW/common/crt0.S" -o "$OUT/fw/obj/crt0.o"
$CC $CFLAGS -c "$FW/common/lib.c" -o "$OUT/fw/obj/lib.o"

build() { # name, kernel object
  want=$(awk -v n="$1" '$1 == n { print $2 }' "$FW/c/expected.txt")
  $CC $CFLAGS -DBENCH_NAME="\"$1\"" -DEXPECTED="${want}ull" -c "$FW/c/main.c" -o "$OUT/fw/obj/main-$1.o"
  $CC $LDFLAGS "$OUT/fw/obj/crt0.o" "$OUT/fw/obj/main-$1.o" "$2" "$OUT/fw/obj/lib.o" -lgcc -o "$OUT/fw/$1.elf"
}

n=0
for b in qsort matmul crc32 sieve fib chase bsearch strings div; do
  $CC $CFLAGS -c "$FW/c/$b.c" -o "$OUT/fw/obj/$b.o"
  build $b "$OUT/fw/obj/$b.o"
  n=$((n+1))
done
for z in nqueens sha256; do
  "$ZIG" build-obj -target riscv64-freestanding-none -mcpu=generic_rv64+m-a-c-d-f -O ReleaseFast \
    -mcmodel=medany -fno-PIC -fstrip "$FW/zig/$z.zig" -femit-bin="$OUT/fw/obj/$z.o"
  build $z "$OUT/fw/obj/$z.o"
  n=$((n+1))
done
echo "built $n programs into $OUT/fw"
