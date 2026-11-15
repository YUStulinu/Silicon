/* Bare-metal programs for Silicon: everything in RAM at 0x80000000. */
OUTPUT_ARCH("riscv")
ENTRY(_start)

SECTIONS
{
  . = 0x80000000;
  .text.init : { *(.text.init) }
  .text : { *(.text .text.*) }
  .rodata : { *(.rodata .rodata.* .srodata .srodata.*) }
  . = ALIGN(64);
  .tohost : { *(.tohost) }
  . = ALIGN(64);
  .data : { *(.data .data.* .sdata .sdata.*) }
  .bss : {
    __bss_start = .;
    *(.bss .bss.* .sbss .sbss.* COMMON)
    . = ALIGN(8);
    __bss_end = .;
  }
  . = ALIGN(16);
  . = . + 0x10000;
  __stack_top = .;
  _end = .;
}
