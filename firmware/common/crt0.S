# Start-up code: a stack, a zeroed .bss, main(), then the exit code to tohost
# ((code << 1) | 1, so 1 means success, as in the riscv-tests).
  .section .text.init
  .globl _start
_start:
  la sp, __stack_top
  la t0, trap
  csrw mtvec, t0
  la t0, __bss_start
  la t1, __bss_end
1:
  bgeu t0, t1, 2f
  sd zero, 0(t0)
  addi t0, t0, 8
  j 1b
2:
  call main
  slli a0, a0, 1
  ori a0, a0, 1
  la t0, tohost
3:
  sw a0, 0(t0)
  j 3b

# any trap is a failure: report the cause (+ 100) as the exit code
  .align 2
trap:
  csrr a0, mcause
  addi a0, a0, 100
  slli a0, a0, 1
  ori a0, a0, 1
  la t0, tohost
4:
  sw a0, 0(t0)
  j 4b

  .section .tohost, "aw", @progbits
  .align 6
  .globl tohost
tohost: .dword 0
  .align 6
  .globl fromhost
fromhost: .dword 0
