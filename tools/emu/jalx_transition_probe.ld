ENTRY(_start)
SECTIONS
{
    . = 0x00400000;
    .text : { *(.text) }
    . = 0x40d50000;
    .trampoline : { *(.trampoline) }
    . = 0x40d54f2c;
    .mips16_target : { *(.mips16_target) }
    /DISCARD/ : { *(.MIPS.abiflags) *(.reginfo) *(.pdr) *(.comment) *(.gnu.attributes) *(.mdebug*) }
}
