ENTRY(_start)
SECTIONS {
  . = 0x80010000;
  .text : { *(.text) }
  . = 0x80012000;
  .callback : { KEEP(*(.callback)) }
  . = 0x80020000;
  .rodata : { *(.rodata) }
  . = 0x8fc30000;
  .driver : { KEEP(*(.driver)) }
  /DISCARD/ : { *(.MIPS.abiflags) *(.reginfo) *(.pdr) *(.comment) *(.gnu.attributes) }
}
