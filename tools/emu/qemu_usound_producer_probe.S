/* Execute the real usound.drv software-ring producer under QEMU. */
	.set	mips32
	.set	noreorder
	.set	noat

	.macro completion_case input, remaining, ready, clock, stop_calls
	lui	$t3, 0x8006
	ori	$t3, $t3, 0x4000
	lui	$t7, 0x8006
	ori	$t7, $t7, 0xf000
	li	$t1, \input
	sw	$t1, 0($t7)
	li	$t1, 1
	sw	$t1, 4($t7)
	sw	$zero, 0x30($t7)
	li	$t1, 0xa5
	sw	$t1, 0x10($t3)
	li	$t1, 0x10
	sw	$t1, 4($t3)
	jalx	0x8005125c
	 nop
	lw	$t1, 0($t7)
	li	$t5, \remaining
	bne	$t1, $t5, fail_completion
	 nop
	lw	$t1, 4($t7)
	li	$t5, \ready
	bne	$t1, $t5, fail_completion
	 nop
	lw	$t1, 0x30($t7)
	li	$t5, \stop_calls
	bne	$t1, $t5, fail_completion
	 nop
	lw	$t1, 0x10($t3)
	li	$t5, \clock
	bne	$t1, $t5, fail_completion
	 nop
	lw	$t1, 4($t3)
	li	$t5, 0x414
	bne	$t1, $t5, fail_completion
	 nop
	.endm

	.text
	.globl	_start
	.ent	_start
_start:
	lui	$sp, 0x8004
	ori	$sp, $sp, 0xfff0
	lui	$t0, 0x8006

	/* State defaults: status=0, stop=0, format=direct, ready/reset=0.
	 * The real poll callback owns the status handshake.  The guest shim is
	 * deliberately a no-op, so enter with the poll-pending bit clear. */
	sw	$zero, 0($t0)
	sw	$zero, 4($t0)
	lui	$t6, 0x8006
	ori	$t6, $t6, 0x2000
	sb	$zero, 0x20($t6)
	sb	$zero, 0x24($t0)
	sb	$zero, 0x28($t0)
	sw	$zero, 0x18($t0)
	sw	$zero, 0x20($t0)

	/* First producer call: 16 bytes, linear destination. */
	li	$t1, 0x10
	sb	$t1, 8($t0)
	sb	$zero, 9($t0)
	jalx	0x80050dc4
	 nop
	lw	$t1, 0x18($t0)
	li	$t2, 0x10
	bne	$t1, $t2, fail_linear
	 nop
	lw	$t1, 0x20($t0)
	bne	$t1, $t2, fail_linear
	 nop

	/* Second call: start at 0x6e30 and force a 0x20-byte ring split. */
	li	$t1, 0x6e30
	sw	$t1, 0x18($t0)
	li	$t1, 0x20
	sb	$t1, 8($t0)
	sw	$zero, 0($t0)
	jalx	0x80050dc4
	 nop
	lw	$t1, 0x18($t0)
	li	$t2, 0x10
	bne	$t1, $t2, fail_wrap
	 nop
	lw	$t1, 0x20($t0)
	li	$t2, 0x30
	bne	$t1, $t2, fail_wrap
	 nop
	lbu	$t1, 0x24($t0)
	bnez	$t1, fail_ready
	 nop

	/* Third call: exercise the real 3-to-4 expansion path. */
	sw	$zero, 0x18($t0)
	sw	$zero, 0x20($t0)
	sw	$zero, 0x24($t0)
	li	$t1, 1
	lui	$t6, 0x8006
	ori	$t6, $t6, 0x2000
	sb	$t1, 0x20($t6)
	li	$t1, 0xaaaaaaab
	sw	$t1, 0x14($t0)
	li	$t1, 0x0c
	sb	$t1, 8($t0)
	sb	$zero, 9($t0)
	sw	$zero, 0($t0)
	jalx	0x80050dc4
	 nop
	lw	$t1, 0x18($t0)
	li	$t2, 0x10
	bne	$t1, $t2, fail_expand_write
	 nop
	lw	$t1, 0x20($t0)
	bne	$t1, $t2, fail_expand_available
	 nop
	lbu	$t1, 0x24($t0)
	bnez	$t1, fail_expand_ready
	 nop

	/* Execute the actual DMA setup twice; all MMIO pointers are relocated to guest RAM. */
	lui	$t3, 0x8006
	ori	$t3, $t3, 0x4000
	li	$t1, 0x10
	sw	$t1, 4($t3)
	li	$t1, 0x20
	sw	$t1, 8($t3)
	sw	$zero, 0x40($t3)
	sw	$zero, 0x44($t3)
	sw	$zero, 0x54($t3)
	lui	$t4, 0x8006
	ori	$t4, $t4, 0x5004
	lui	$t6, 0x8006
	ori	$t6, $t6, 0x2000
	sw	$zero, 0($t3)
	sw	$zero, 0($t4)
	lui	$t6, 0x8006
	ori	$t6, $t6, 0x2000
	sb	$zero, 0x20($t6)
	jalx	0x800512e0
	 nop
	lw	$t1, 0x40($t3)
	li	$t5, 0x4b03
	bne	$t1, $t5, fail_dma16
	 nop
	lw	$t1, 0x54($t3)
	li	$t5, 14112
	bne	$t1, $t5, fail_dma16
	 nop
	lw	$t1, 0($t3)
	li	$t5, 1
	bne	$t1, $t5, fail_dma16
	 nop
	lui	$t4, 0x8006
	ori	$t4, $t4, 0x5004
	lw	$t1, 0($t4)
	li	$t5, 1
	bne	$t1, $t5, fail_dma16
	 nop
	lw	$t1, 0x44($t3)
	li	$t5, 0x80061000
	bne	$t1, $t5, fail_dma16
	 nop
	lw	$t1, 4($t3)
	li	$t5, 0x414
	bne	$t1, $t5, fail_dma16
	 nop
	lw	$t1, 8($t3)
	li	$t5, 0x424
	bne	$t1, $t5, fail_dma16
	 nop

	/* Reset every observed register so format-1 writes are independently tested. */
	lui	$t3, 0x8006
	ori	$t3, $t3, 0x4000
	li	$t1, 0x10
	sw	$t1, 4($t3)
	li	$t1, 0x20
	sw	$t1, 8($t3)
	sw	$zero, 0($t3)
	sw	$zero, 0x40($t3)
	sw	$zero, 0x44($t3)
	sw	$zero, 0x54($t3)
	lui	$t4, 0x8006
	ori	$t4, $t4, 0x5004
	sw	$zero, 0($t4)
	li	$t1, 1
	lui	$t6, 0x8006
	ori	$t6, $t6, 0x2000
	sb	$t1, 0x20($t6)
	jalx	0x800512e0
	 nop
	lw	$t1, 0x40($t3)
	li	$t5, 0x8b03
	bne	$t1, $t5, fail_dma24
	 nop
	lw	$t1, 0x54($t3)
	li	$t5, 7056
	bne	$t1, $t5, fail_dma24
	 nop
	lw	$t1, 0($t3)
	li	$t5, 1
	bne	$t1, $t5, fail_dma24
	 nop
	lui	$t4, 0x8006
	ori	$t4, $t4, 0x5004
	lw	$t1, 0($t4)
	li	$t5, 1
	bne	$t1, $t5, fail_dma24
	 nop
	lw	$t1, 0x44($t3)
	li	$t5, 0x80061000
	bne	$t1, $t5, fail_dma24
	 nop
	lw	$t1, 4($t3)
	li	$t5, 0x414
	bne	$t1, $t5, fail_dma24
	 nop
	lw	$t1, 8($t3)
	li	$t5, 0x424
	bne	$t1, $t5, fail_dma24
	 nop

	/* Exercise empty, minimum positive remainder, and both clock threshold edges. */
	completion_case 0x3720, 0, 0, 0xa5, 1
	completion_case 0x3721, 1, 1, 0xa4, 0
	completion_case 0x4859, 4409, 1, 0xa4, 0
	completion_case 0x485a, 4410, 1, 0xa5, 0
	completion_case 0x5d06, 9702, 1, 0xa5, 0
	completion_case 0x5d07, 9703, 1, 0xa7, 0

	la	$a0, pass_text
	jal	puts
	 nop
	b	halt
	 nop

fail_linear:
	la	$a0, linear_text
	b	fail
	 nop
fail_wrap:
	la	$a0, wrap_text
	b	fail
	 nop
fail_ready:
	la	$a0, ready_text
	b	fail
	 nop
fail_expand_write:
	la	$a0, expand_write_text
	b	fail
	 nop

fail_expand_available:
	la	$a0, expand_available_text
	b	fail
	 nop
fail_expand_ready:
	la	$a0, expand_ready_text
	b	fail
	 nop
fail_dma16:
	la	$a0, dma16_text
	b	fail
	 nop
fail_dma24:
	la	$a0, dma24_text
	b	fail
	 nop
fail_completion:
	la	$a0, completion_text
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

	/* All peripheral/provider callbacks are deliberately no-op MIPS16 stubs. */
	.section .callback,"ax"
	.set	mips16
	.globl	usound_callback_stub
	.ent	usound_callback_stub
usound_callback_stub:
	jr	$ra
	 nop
	.end	usound_callback_stub
	.set	mips32

	.section .rodata
pass_text:
	.asciz	"REAL USOUND PRODUCER PASS function=FUN_bfc30dc4 linear=1 wrap=1 expand=1 dma-init=2 completion=6\r\n"
linear_text:
	.asciz	"REAL USOUND PRODUCER FAIL linear\r\n"
wrap_text:
	.asciz	"REAL USOUND PRODUCER FAIL wrap\r\n"
ready_text:
	.asciz	"REAL USOUND PRODUCER FAIL ready\r\n"
expand_write_text:
	.asciz	"REAL USOUND PRODUCER FAIL expand-write\r\n"
expand_available_text:
	.asciz	"REAL USOUND PRODUCER FAIL expand-available\r\n"
expand_ready_text:
	.asciz	"REAL USOUND PRODUCER FAIL expand-ready\r\n"
dma16_text:
	.asciz	"REAL USOUND DMA SETUP FAIL format0\r\n"
dma24_text:
	.asciz	"REAL USOUND DMA SETUP FAIL format1\r\n"
completion_text:
	.asciz	"REAL USOUND DMA COMPLETION FAIL boundary\r\n"

	.section .driver,"ax"
	.align	2
	.incbin	"usound_code.bin"
