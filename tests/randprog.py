"""Random RV64IM programs for co-simulation.

Each program is straight-line code mixed with forward branches, small loops,
calls and returns, indirect jumps, loads and stores to a 4 KiB buffer (some
with addresses computed from data, so loads meet in-flight stores to the same
place), multiplications and divisions, CSR accesses, fences and traps
(illegal instructions and ebreak, which the handler skips). It ends with an
ecall, which reports success through tohost. The processor and the
reference model must agree on every instruction.

    python3 tests/randprog.py SEED OUT.S [--length N]
"""

from __future__ import annotations

import argparse
import random

# x0 zero, x1 ra, x2 sp, x3 gp, x4 tp, x8 base of the buffer, x9 loop counter,
# x30/x31 for the trap handler
POOL = [5, 6, 7] + list(range(10, 30))

ALU_RR = ["add", "sub", "and", "or", "xor", "sll", "srl", "sra", "slt", "sltu", "addw", "subw", "sllw", "srlw", "sraw"]
ALU_RI = ["addi", "andi", "ori", "xori", "slti", "sltiu", "addiw"]
SHIFT_I = [("slli", 63), ("srli", 63), ("srai", 63), ("slliw", 31), ("srliw", 31), ("sraiw", 31)]
MULDIV = ["mul", "mulh", "mulhsu", "mulhu", "mulw", "div", "divu", "rem", "remu", "divw", "divuw", "remw", "remuw"]
LOADS = [("lb", 1), ("lbu", 1), ("lh", 2), ("lhu", 2), ("lw", 4), ("lwu", 4), ("ld", 8)]
STORES = [("sb", 1), ("sh", 2), ("sw", 4), ("sd", 8)]
BRANCHES = ["beq", "bne", "blt", "bge", "bltu", "bgeu"]


class Gen:
    def __init__(self, seed: int):
        self.r = random.Random(seed)
        self.out: list[str] = []
        self.label = 0
        self.funcs: list[list[str]] = []

    def reg(self) -> str:
        return f"x{self.r.choice(POOL)}"

    def new_label(self) -> str:
        self.label += 1
        return f"L{self.label}"

    def emit(self, s: str) -> None:
        self.out.append("  " + s)

    def simple(self, depth: int = 0) -> None:
        r = self.r
        k = r.random()
        if k < 0.30:
            self.emit(f"{r.choice(ALU_RR)} {self.reg()}, {self.reg()}, {self.reg()}")
        elif k < 0.45:
            self.emit(f"{r.choice(ALU_RI)} {self.reg()}, {self.reg()}, {r.randint(-2048, 2047)}")
        elif k < 0.52:
            op, m = r.choice(SHIFT_I)
            self.emit(f"{op} {self.reg()}, {self.reg()}, {r.randint(0, m)}")
        elif k < 0.55:
            self.emit(f"lui {self.reg()}, {r.randint(0, 0xFFFFF)}")
        elif k < 0.57:
            self.emit(f"auipc {self.reg()}, {r.randint(0, 0xFFFFF)}")
        elif k < 0.65:
            self.emit(f"{r.choice(MULDIV)} {self.reg()}, {self.reg()}, {self.reg()}")
        elif k < 0.80:
            self.memory()
        elif k < 0.82:
            self.emit(f"csrrw {self.reg()}, mscratch, {self.reg()}")
        elif k < 0.83:
            self.emit(f"csrr {self.reg()}, mscratch")
        elif k < 0.84:
            self.emit("fence")
        else:
            self.memory()

    def memory(self) -> None:
        r = self.r
        if r.random() < 0.5:
            op, size = r.choice(LOADS + STORES)
            off = r.randrange(0, 2048 - 8) // size * size
            if op.startswith("s") and op != "sltu":
                self.emit(f"{op} {self.reg()}, {off}(x8)")
            else:
                self.emit(f"{op} {self.reg()}, {off}(x8)")
        else:
            # an address computed from data: aligned, inside the buffer
            op, size = r.choice(LOADS + STORES)
            t = self.reg()
            src = self.reg()
            mask = (0xFF8 // size * size) & 0x7F8 if size == 8 else (0x7FF // size) * size
            self.emit(f"andi {t}, {src}, {mask}")
            self.emit(f"add {t}, {t}, x8")
            v = self.reg()
            while v == t and op[0] == "l":
                v = self.reg()
            self.emit(f"{op} {v}, 0({t})")

    def block(self, n: int, depth: int = 0) -> None:
        r = self.r
        i = 0
        while i < n:
            k = r.random()
            if k < 0.08 and depth < 2:
                # forward branch over a few instructions
                lab = self.new_label()
                self.emit(f"{r.choice(BRANCHES)} {self.reg()}, {self.reg()}, {lab}")
                self.block(r.randint(1, 5), depth + 1)
                self.out.append(f"{lab}:")
            elif k < 0.12 and depth == 0:
                # a short loop
                lab = self.new_label()
                self.emit(f"li x9, {r.randint(1, 6)}")
                self.out.append(f"{lab}:")
                self.block(r.randint(2, 8), depth + 1)
                self.emit("addi x9, x9, -1")
                self.emit(f"bnez x9, {lab}")
            elif k < 0.15 and depth == 0:
                # a call to a leaf function
                name = f"f{len(self.funcs)}"
                saved = self.out
                self.out = [f"{name}:"]
                self.block(r.randint(1, 6), 2)
                self.emit("ret")
                self.funcs.append(self.out)
                self.out = saved
                self.emit(f"call {name}")
            elif k < 0.16:
                # an indirect jump
                lab = self.new_label()
                t = self.reg()
                self.emit(f"la {t}, {lab}")
                self.emit(f"jr {t}")
                self.emit(f"{r.choice(ALU_RR)} {self.reg()}, {self.reg()}, {self.reg()}")
                self.out.append(f"{lab}:")
            elif k < 0.165:
                self.emit(".word 0x00000000")  # illegal: the handler skips it
            elif k < 0.17:
                self.emit("ebreak")
            elif k < 0.172:
                self.emit("fence.i")
            else:
                self.simple(depth)
            i += 1

    def program(self, length: int) -> str:
        r = self.r
        head = [
            "  .section .text.init",
            "  .globl _start",
            "_start:",
            "  la x30, handler",
            "  csrw mtvec, x30",
            "  la x8, buffer",
        ]
        for x in POOL:
            head.append(f"  li x{x}, {r.getrandbits(64) - (1 << 63)}")
        self.block(length)
        tail = [
            "  ecall",
            "  j _start",
            "  .align 2",
            "handler:",
            "  csrr x30, mcause",
            "  li x31, 11",
            "  beq x30, x31, pass",
            "  csrr x30, mepc",
            "  addi x30, x30, 4",
            "  csrw mepc, x30",
            "  mret",
            "pass:",
            "  li x30, 1",
            "  la x31, tohost",
            "1:",
            "  sw x30, 0(x31)",
            "  j 1b",
        ]
        funcs = [line for f in self.funcs for line in f]
        data = [
            "  .data",
            "  .align 6",
            "buffer:",
            *[f"  .dword {r.getrandbits(64)}" for _ in range(512)],
            "  .section .tohost, \"aw\", @progbits",
            "  .align 6",
            "  .globl tohost",
            "tohost: .dword 0",
            "  .align 6",
            "  .globl fromhost",
            "fromhost: .dword 0",
        ]
        return "\n".join(head + self.out + tail + funcs + data) + "\n"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("seed", type=int)
    ap.add_argument("out")
    ap.add_argument("--length", type=int, default=400)
    a = ap.parse_args()
    with open(a.out, "w", encoding="utf-8") as f:
        f.write(Gen(a.seed).program(a.length))


if __name__ == "__main__":
    main()
