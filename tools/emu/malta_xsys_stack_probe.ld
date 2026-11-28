OUTPUT_ARCH(mips)
ENTRY(_start)
SECTIONS
{
    . = 0xbfc00000;
    .reset : { KEEP(*(.reset)) }
    . = 0xbfc01000;
    .text : { *(.text) }
    /DISCARD/ : { *(.reginfo) *(.MIPS.abiflags) *(.pdr) *(.comment) }
}
