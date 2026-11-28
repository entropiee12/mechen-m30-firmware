ENTRY(_start)
SECTIONS
{
    . = 0x80010000;
    .text : { *(.text) }
    . = 0x80020000;
    .rodata : { *(.rodata) }
    . = 0x80050000;
    .helper : { KEEP(*(.helper)) }
    . = 0x80060000;
    .source : { KEEP(*(.source)) }
    /DISCARD/ : { *(.MIPS.abiflags) *(.reginfo) *(.pdr) *(.comment) *(.gnu.attributes) *(.mdebug*) }
}
