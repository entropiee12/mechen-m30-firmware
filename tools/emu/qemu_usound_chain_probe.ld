ENTRY(_start)
SECTIONS
{
    . = 0x80010000;
    .text : { *(.text) }
    . = 0x80011180;
    .vectors : { KEEP(*(.vectors)) }
    . = 0x80020000;
    .rodata : { *(.rodata) }
    . = 0x80050000;
    .driver : { KEEP(*(.driver)) }
    /DISCARD/ : { *(.MIPS.abiflags) *(.reginfo) *(.pdr) *(.comment) *(.gnu.attributes) *(.mdebug*) }
}
