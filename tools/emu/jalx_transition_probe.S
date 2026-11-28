/* Minimal QEMU ISA-transition probe; this is not firmware. */
	.set	mips32
	.set	noreorder
	.text
	.globl	_start
	.ent	_start
_start:
	/* Enter the high trampoline with an ordinary MIPS32 indirect call. */
	lui	$t9, 0x40d5
	ori	$t9, $t9, 0x0000
	jalr	$t9
	nop
	/* A successful MIPS16 return leaves the marker in v0. */
	andi	$a0, $v0, 0xffff
	ori	$v0, $zero, 4001
	syscall
	nop
	.end	_start

	/* Same 256 MiB region as the M30 bank entry: JALX can encode this target. */
	.section .trampoline,"ax"
	.set	mips32
	.set	noreorder
	.globl	mips32_trampoline
	.ent	mips32_trampoline
mips32_trampoline:
	/* JALX overwrites ra with the MIPS32 return point. */
	addu	$s0, $ra, $zero
	jalx	0x40d54f2c
	nop
	/* Returned in MIPS32 mode if QEMU handled the transition correctly. */
	jr	$s0
	nop
	.end	mips32_trampoline

	.section .mips16_target,"ax"
	.set	mips16
	.set	noreorder
	.globl	mips16_target
	.ent	mips16_target
mips16_target:
	li	$v0, 0x1234
	jr	$ra
	nop
	.end	mips16_target
