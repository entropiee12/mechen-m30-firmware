/* Filled by make_qemu_probe.py with an INCLUDE of the generated AP banks. */
ENTRY(_start)
SECTIONS
{
    . = 0x00400000;
    .text : { qemu_probe_start.o(.text) }
    . = 0x40d50000;
    .trampoline : { qemu_probe_start.o(.trampoline) }
    . = 0x00700000;
    .stubs : { qemu_probe.o(.stubs) }
    . = 0x10002000;
    .ram : { qemu_probe_start.o(.ram) }
    . = 0x10002800;
    .probe_stack : { qemu_probe_start.o(.probe_stack) }
    . = 0x10003fdc;
    .probe_zero_shim : { qemu_probe_start.o(.probe_zero_shim) }
    . = 0x10003ff0;
    .trampoline_ram : { qemu_probe_start.o(.trampoline_ram) }
    . = 0x10003ff4;
    .probe_result : { qemu_probe_start.o(.probe_result) }
    INCLUDE banks.ld
    /DISCARD/ : { *(.MIPS.abiflags) *(.reginfo) *(.pdr) *(.comment) *(.gnu.attributes) *(.mdebug*) }
}
