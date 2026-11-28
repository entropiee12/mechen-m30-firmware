/* Execute the real usound.drv ring consumer with a relocated state pool. */
	.set	mips32
	.set	noreorder
	.set	noat

	.section .vectors,"ax"
	.globl	general_exception
	.ent	general_exception
general_exception:
	/* Record the real bfc326a9 syscall envelope, then return to JALRC's RA. */
	lui	$k0, 0x8006
	addiu	$k0, $k0, 0x2000
	lw	$k1, 0x0($k0)
	addiu	$k1, $k1, 1
	sw	$k1, 0x0($k0)
	lw	$k1, 0x4($k0)
	addu	$k1, $k1, $a2
	sw	$k1, 0x4($k0)
	sw	$v1, 0x8($k0)
	mtc0	$ra, $14
	nop
	eret
	nop
	.end	general_exception

	.text
	.globl	_start
	.ent	_start
_start:
	lui	$sp, 0x8004
	ori	$sp, $sp, 0xfff0
	/* Use a RAM-backed exception vector instead of Malta's PROM. */
	lui	$k0, 0x8001
	ori	$k0, $k0, 0x1000
	mtc0	$k0, $15, 1
	nop
	mfc0	$k1, $12
	li	$t1, 0xffbfffff
	and	$k1, $k1, $t1
	mtc0	$k1, $12
	nop

	/* First call: consume 16 bytes without wrapping. */	lui	$t0, 0x8006
	sw	$zero, 0x2000($t0)
	sw	$zero, 0x2004($t0)
	sw	$zero, 0x2008($t0)
	ori	$t1, $zero, 16
	sw	$t1, 0($t0)
	sw	$zero, 4($t0)
	sw	$zero, 8($t0)
	sb	$zero, 0xc($t0)
	lui	$a1, 0x8003
	addiu	$a1, $a1, 0x100
	or	$a2, $t0, $zero
	jalx	0x80051654
	 nop
	li	$t1, 16
	bne	$v0, $t1, fail_first
	 nop
	lw	$t1, 0($t0)
	bnez	$t1, fail_first
	 nop
	lw	$t1, 8($t0)
	lw	$t2, 0x2000($t0)
	li	$t3, 1
	bne	$t2, $t3, fail_first
	 nop
	lw	$t2, 0x2004($t0)
	li	$t3, 16
	bne	$t2, $t3, fail_first
	 nop
	lw	$t2, 0x2008($t0)
	li	$t3, 0x10
	bne	$t2, $t3, fail_first
	 nop

	/* Second call: 32 bytes from read offset 0xff8, forcing two callbacks. */	li	$t2, 32
	sw	$t2, 0($t0)
	li	$t2, 0xff8
	sw	$t2, 8($t0)
	li	$t2, 32
	sw	$t2, 0x20($t0)
	or	$a2, $t0, 0x20
	jalx	0x80051654
	 nop
	li	$t1, 32
	bne	$v0, $t1, fail_wrap
	 nop
	lw	$t1, 0($t0)
	bnez	$t1, fail_wrap
	 nop
	lw	$t1, 8($t0)
	/* The real function resets the pointer to zero after crossing 0x1000. */	li	$t2, 0
	bne	$t1, $t2, fail_wrap
	 nop
	lw	$t2, 0x2000($t0)
	li	$t3, 3
	bne	$t2, $t3, fail_wrap
	 nop
	lw	$t2, 0x2004($t0)
	li	$t3, 48
	bne	$t2, $t3, fail_wrap
	 nop
	lw	$t2, 0x2008($t0)
	li	$t3, 0x10
	bne	$t2, $t3, fail_wrap
	 nop

	la	$a0, pass_text
	jal	puts
	 nop
	b	halt
	 nop

fail_first:
	la	$a0, first_text
	b	fail
	 nop
fail_wrap:
	la	$a0, wrap_text
fail:
	jal	puts
	 nop

halt:
	1:
	b	1b
	 nop
	.end	_start

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
	.asciz	"REAL USOUND RING PASS consumer=FUN_bfc31654 wrap=1 callbacks=2\r\n"
first_text:
	.asciz	"REAL USOUND RING FAIL first\r\n"
wrap_text:
	.asciz	"REAL USOUND RING FAIL wrap\r\n"

	.section .driver,"ax"
	.align	2
	.incbin	"usound_code.bin"
