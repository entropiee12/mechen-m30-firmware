/*
 * Execute two dependency-free MIPS16 functions copied from the real
 * usound.drv under QEMU/Malta. The wrapper supplies only guest RAM buffers;
 * it does not execute the driver entry point or touch any M30 peripheral.
 */
	.set	mips32
	.set	noreorder
	.set	noat

	.text
	.globl	_start
	.ent	_start
_start:
	lui	$sp, 0x8004
	ori	$sp, $sp, 0xfff0

	/* FUN_bfc30cac(source, destination, 17). */
	la	$a0, direct_source
	la	$a1, direct_output
	li	$a2, 17
	jalx	0x80050cac
	 nop
	la	$a0, direct_output
	la	$a1, direct_expected
	li	$a2, 17
	jal	verify_bytes
	 nop
	bnez	$v0, fail_copy
	 nop

	/* FUN_bfc30ce8(source, destination, 6) emits two four-byte groups. */
	la	$a0, expand_source
	la	$a1, expand_output
	li	$a2, 6
	jalx	0x80050ce8
	 nop
	la	$a0, expand_output
	la	$a1, expand_expected
	li	$a2, 8
	jal	verify_bytes
	 nop
	bnez	$v0, fail_expand
	 nop

	la	$a0, pass_text
	jal	puts
	 nop
	b	halt
	 nop

fail_copy:
	la	$a0, copy_text
	b	fail
	 nop
fail_expand:
	la	$a0, expand_text
fail:
	jal	puts
	 nop

halt:
	/* The host wrapper stops QEMU after observing the result line. */	1:
	b	1b
	 nop
	.end	_start

	.ent	verify_bytes
verify_bytes:
	or	$t0, $a0, $zero
	or	$t1, $a1, $zero
	or	$t2, $zero, $zero
verify_loop:
	lbu	$t3, 0($t0)
	lbu	$t4, 0($t1)
	bne	$t3, $t4, verify_bad
	 nop
	addiu	$t0, $t0, 1
	addiu	$t1, $t1, 1
	addiu	$t2, $t2, 1
	slt	$t5, $t2, $a2
	bnez	$t5, verify_loop
	 nop
	or	$v0, $zero, $zero
	jr	$ra
	 nop
verify_bad:
	li	$v0, 1
	jr	$ra
	 nop
	.end	verify_bytes

	.ent	puts
puts:
	or	$t0, $a0, $zero
putc:
	lui	$t1, 0xbf00
	ori	$t1, $t1, 0x0900
	lbu	$t3, 0($t0)
	beqz	$t3, puts_done
	 nop
	sb	$t3, 0($t1)
	addiu	$t0, $t0, 1
	b	putc
	 nop
puts_done:
	jr	$ra
	 nop
	.end	puts

	.section .rodata
pass_text:
	.asciz	"REAL USOUND MIPS16 LEAF PASS copy=FUN_bfc30cac expand=FUN_bfc30ce8\r\n"
copy_text:
	.asciz	"REAL USOUND MIPS16 LEAF FAIL copy\r\n"
expand_text:
	.asciz	"REAL USOUND MIPS16 LEAF FAIL expand\r\n"

	.section .data
	.align	2
direct_source:
	.byte	0x03,0x14,0x25,0x36,0x47,0x58,0x69,0x7a,0x8b,0x9c,0xad,0xbe,0xcf,0xd0,0xe1,0xf2,0x04
direct_output:
	.space	17
direct_expected:
	.byte	0x03,0x14,0x25,0x36,0x47,0x58,0x69,0x7a,0x8b,0x9c,0xad,0xbe,0xcf,0xd0,0xe1,0xf2,0x04
	.align	2
expand_source:
	.byte	0x11,0x22,0x33,0x44,0x55,0x66
expand_output:
	.space	8
expand_expected:
	.byte	0x00,0x11,0x22,0x33,0x00,0x44,0x55,0x66

	.section .driver,"ax"
	.align	2
	/* The wrapper script extracts the real 0xbfc30000 segment here. */
	.incbin	"usound_code.bin"
