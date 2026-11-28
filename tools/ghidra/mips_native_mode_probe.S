# Reference-only assembly for the bytes embedded in RunMipsNativeModeProbe.java.
# The headless runner stays standalone and does not assemble this file.
.set noreorder
.set noat
.text
.globl _start
.ent _start
.set mips16
_start:
    jalx mips32_target
    nop
    nop
.end _start

.set nomips16
.ent mips32_target
mips32_target:
    addiu $v0, $zero, 0x1234
    jr $ra
    nop
.end mips32_target
