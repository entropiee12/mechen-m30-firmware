/* Execute the real usound.drv word-port transfer function under QEMU. */
	.set	mips32
	.set	noreorder
	.set	noat

	.text
	.globl	_start
	.ent	_start
_start:
	lui	$sp, 0x8004
	ori	$sp, $sp, 0xfff0
	lui	$t0, 0x8006
	sw	$zero, 0($t0)

	/* Mode 2: read two words from the data port. */
	lui	$t1, 0x1122
	ori	$t1, $t1, 0x3344
	sw	$t1, 4($t0)
	lui	$a1, 0x8003
	li	$a0, 2
	li	$a2, 8
	jalx	0x800518cc
	 nop
	lw	$t2, 0($a1)
	bne	$t2, $t1, fail_data_read
	 nop
	lw	$t2, 4($a1)
	bne	$t2, $t1, fail_data_read
	 nop

	/* Mode 1: write two words; the final word remains in the shared port. */
	lui	$t2, 0xdead
	ori	$t2, $t2, 0xbeef
	sw	$t2, 0($a1)
	lui	$t3, 0x1234
	ori	$t3, $t3, 0x5678
	sw	$t3, 4($a1)
	li	$a0, 1
	li	$a2, 8
	jalx	0x800518cc
	 nop
	lw	$t2, 4($t0)
	bne	$t2, $t3, fail_data_write
	 nop

	/* Mode 4: read one word from the alternate port. */
	lui	$t1, 0xaabb
	ori	$t1, $t1, 0xccdd
	sw	$t1, 8($t0)
	li	$a0, 4
	li	$a2, 4
	jalx	0x800518cc
	 nop
	lw	$t2, 0($a1)
	bne	$t2, $t1, fail_alternate
	 nop

	/* Mode 2 is gated when the recovered gate byte equals one. */
	li	$t2, 1
	sb	$t2, 0($t0)
	li	$t2, 0x5555
	sw	$t2, 0($a1)
	lui	$t1, 0x9999
	ori	$t1, $t1, 0xaaaa
	sw	$t1, 4($t0)
	li	$a0, 2
	li	$a2, 4
	jalx	0x800518cc
	 nop
	lw	$t2, 0($a1)
	li	$t3, 0x5555
	bne	$t2, $t3, fail_gate
	 nop

	la	$a0, pass_text
	jal	puts
	 nop
	b	halt
	 nop

fail_data_read:
	la	$a0, data_read_text
	b	fail
	 nop
fail_data_write:
	la	$a0, data_write_text
	b	fail
	 nop
fail_alternate:
	la	$a0, alternate_text
	b	fail
	 nop
fail_gate:
	la	$a0, gate_text
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
	.asciz	"REAL USOUND PORT PASS modes=1,2,4 gate=1\r\n"
data_read_text:
	.asciz	"REAL USOUND PORT FAIL data-read\r\n"
data_write_text:
	.asciz	"REAL USOUND PORT FAIL data-write\r\n"
alternate_text:
	.asciz	"REAL USOUND PORT FAIL alternate-read\r\n"
gate_text:
	.asciz	"REAL USOUND PORT FAIL gate\r\n"

	.section .driver,"ax"
	.align	2
	.incbin	"usound_code.bin"
