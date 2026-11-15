#!/bin/sh
# Fetch the official RISC-V ISA tests (riscv-software-src/riscv-tests) at a
# fixed commit into third_party/riscv-tests. Only the test sources are used;
# they are built with this project's own environment (tests/env).
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
DEST=$ROOT/third_party/riscv-tests
REV=3df703c
if [ ! -d "$DEST/.git" ]; then
  mkdir -p "$ROOT/third_party"
  git clone -q https://github.com/riscv-software-src/riscv-tests.git "$DEST"
fi
cd "$DEST"
git checkout -q $REV
git submodule update -q --init env
echo "riscv-tests at $(git rev-parse --short HEAD)"
