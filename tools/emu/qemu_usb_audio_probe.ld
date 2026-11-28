ENTRY(_start)
SECTIONS
{
    . = 0x80010000;
    .text : { *(.text) }
    . = 0x80020000;
    .rodata : { *(.rodata) }
    . = 0x80030000;
    .bss : { *(.bss) }
    /DISCARD/ : { *(.MIPS.abiflags) *(.reginfo) *(.pdr) *(.comment) *(.gnu.attributes) *(.mdebug*) }
}
