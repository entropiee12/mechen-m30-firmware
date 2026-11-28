ENTRY(_start)
SECTIONS
{
    . = 0x80010000;
    .text : { *(.text) }
    . = 0x80020000;
    .rodata : { *(.rodata) }
    . = 0x80050000;
    .driver : { KEEP(*(.driver)) }
    . = 0x8fc18d00;
    .driver_alias : { KEEP(*(.driver_alias)) }
    /DISCARD/ : { *(.MIPS.abiflags) *(.reginfo) *(.pdr) *(.comment) *(.gnu.attributes) *(.mdebug*) }
}
