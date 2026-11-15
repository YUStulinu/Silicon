// A riscv-tests environment for Silicon: everything runs in machine mode.
//
// The official "p" environment drops to user mode with mret and sets up
// satp, PMP and trap delegation. Silicon implements only machine mode, so
// this environment keeps the test in M-mode and installs just the trap
// vector. A test passes by writing 1 to `tohost` from the ecall handler, and
// fails by writing (test number << 1) | 1, exactly as in the "p" environment.

#ifndef _ENV_SILICON_H
#define _ENV_SILICON_H

#include "encoding.h"

#define RVTEST_RV64U .macro init; .endm
#define RVTEST_RV64UF RVTEST_RV64U
#define RVTEST_RV64M .macro init; .endm
#define RVTEST_RV32U .macro init; .endm
#define RVTEST_RV32M .macro init; .endm

#define TESTNUM gp

#define INIT_XREG                                                       \
  li x1, 0; li x2, 0; li x3, 0; li x4, 0; li x5, 0; li x6, 0; li x7, 0; \
  li x8, 0; li x9, 0; li x10, 0; li x11, 0; li x12, 0; li x13, 0;       \
  li x14, 0; li x15, 0; li x16, 0; li x17, 0; li x18, 0; li x19, 0;     \
  li x20, 0; li x21, 0; li x22, 0; li x23, 0; li x24, 0; li x25, 0;     \
  li x26, 0; li x27, 0; li x28, 0; li x29, 0; li x30, 0; li x31, 0;

#define RVTEST_CODE_BEGIN                                               \
        .section .text.init;                                            \
        .align  6;                                                      \
        .weak mtvec_handler;                                            \
        .globl _start;                                                  \
_start:                                                                 \
        j reset_vector;                                                 \
        .align 2;                                                       \
trap_vector:                                                            \
        csrr t5, mcause;                                                \
        li t6, CAUSE_MACHINE_ECALL;                                     \
        beq t5, t6, write_tohost;                                       \
        la t5, mtvec_handler;                                           \
        beqz t5, other_exception;                                       \
        jr t5;                                                          \
other_exception:                                                        \
        ori TESTNUM, TESTNUM, 1337;                                     \
write_tohost:                                                           \
        sw TESTNUM, tohost, t5;                                         \
        sw zero, tohost + 4, t5;                                        \
        j write_tohost;                                                 \
reset_vector:                                                           \
        INIT_XREG;                                                      \
        li TESTNUM, 0;                                                  \
        la t0, trap_vector;                                             \
        csrw mtvec, t0;                                                 \
        init;

#define RVTEST_CODE_END                                                 \
        unimp

#define RVTEST_PASS                                                     \
        fence;                                                          \
        li TESTNUM, 1;                                                  \
        li a7, 93;                                                      \
        li a0, 0;                                                       \
        ecall

#define RVTEST_FAIL                                                     \
        fence;                                                          \
1:      beqz TESTNUM, 1b;                                               \
        sll TESTNUM, TESTNUM, 1;                                        \
        or TESTNUM, TESTNUM, 1;                                         \
        li a7, 93;                                                      \
        addi a0, TESTNUM, 0;                                            \
        ecall

#define EXTRA_DATA

#define RVTEST_DATA_BEGIN                                               \
        EXTRA_DATA                                                      \
        .pushsection .tohost,"aw",@progbits;                            \
        .align 6; .global tohost; tohost: .dword 0; .size tohost, 8;    \
        .align 6; .global fromhost; fromhost: .dword 0; .size fromhost, 8;\
        .popsection;                                                    \
        .align 4; .global begin_signature; begin_signature:

#define RVTEST_DATA_END .align 4; .global end_signature; end_signature:

#endif
