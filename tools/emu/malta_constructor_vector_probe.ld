OUTPUT_ARCH(mips)
ENTRY(_start)
SECTIONS
{
    . = 0xbfc00000;
    .reset : { KEEP(*(.reset)) }
    . = 0xbfc01000;
    .text : { *(.text) }
    . = 0xbfc01200;
    .boundary : { KEEP(*(.boundary)) }
    . = 0xbfc06f18;
    .handler : { KEEP(*(.handler)) }
    . = 0xbfc07000;
    .mips16 : { KEEP(*(.mips16)) }
    /DISCARD/ : { *(.reginfo) *(.MIPS.abiflags) *(.pdr) *(.comment) }
}
