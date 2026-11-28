OUTPUT_ARCH(mips)
ENTRY(_start)
SECTIONS
{
    . = 0xbfc00000;
    .reset : { KEEP(*(.reset)) }
    . = 0xbfc01000;
    .text : { *(.text) }
    . = 0xbfc06d6c;
    .checkpoint : { KEEP(*(.checkpoint)) }
    . = 0xbfc06f18;
    .handler : { KEEP(*(.handler)) }
    /DISCARD/ : { *(.reginfo) *(.MIPS.abiflags) *(.pdr) *(.comment) }
}
