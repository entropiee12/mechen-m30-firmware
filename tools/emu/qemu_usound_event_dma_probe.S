	/* Execute the observed usound event callback and its real producer/DMA path. */
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

	/* Initialize the real producer state and separate synthetic peripherals. */
	sw	$zero, 0($t0)		/* producer status */
	sw	$zero, 4($t0)		/* stop flag */
	sb	$zero, 0x10($t0)	/* format flag */
	sb	$zero, 0x24($t0)	/* DMA ready */
	sb	$zero, 0x28($t0)	/* producer reset */
	sw	$zero, 0x18($t0)	/* write offset */
	sw	$zero, 0x20($t0)	/* queued byte count */
	lui	$t1, 0x8006
	ori	$t1, $t1, 0x2000
	sb	$zero, 0x20($t1)	/* shared producer/DMA format flag */

	/* Seed the staged USB input block with a recognizable byte pattern. */
	lui	$t1, 0x8006
	ori	$t1, $t1, 0x8000
	li	$t2, 0x1000
	li	$t3, 0x5a5a5a5a
seed_scratch:
	sw	$t3, 0($t1)
	addiu	$t1, $t1, 4
	addiu	$t2, $t2, -4
	bnez	$t2, seed_scratch
	 nop

	/* Four DMA registers are RAM aliases; start from an observable zero state. */
	lui	$t0, 0x8006
	ori	$t0, $t0, 0x4000
	sw	$zero, 0x40($t0)	/* channel control */
	sw	$zero, 0x44($t0)	/* DMA ring address */
	sw	$zero, 0x54($t0)	/* DMA size */

	/*
	 * 74 callback events x 192 bytes = 0x3780 bytes. This crosses the real
	 * direct-format DMA threshold (0x3720) by 0x60, as in the existing stream
	 * probe, while entering through FUN_bfc31114 on every iteration.
	 */
	lui	$t0, 0x8006
	ori	$t0, $t0, 0xf000
	li	$t1, 74
	sw	$t1, 0x10($t0)
event_loop:
	/* FUN_bfc31114 checks bit 0x02 at relocated MMIO 0x80066000. */
	lui	$t0, 0x8006
	ori	$t0, $t0, 0x6000
	li	$t1, 2
	sb	$t1, 0($t0)

	/* The real stream probe clears this producer state before each packet. */
	lui	$t0, 0x8006
	sw	$zero, 0($t0)

	/* Real producer sees a 192-byte USB packet length. */
	lui	$t0, 0x8006
	li	$t1, 0xc0
	sb	$t1, 0x08($t0)
	sb	$zero, 0x09($t0)

	jalx	0x8fc31114		/* real MIPS16 FUN_bfc31114 */
	 nop

	lui	$t0, 0x8006
	ori	$t0, $t0, 0xf000
	lw	$t1, 0x10($t0)
	addiu	$t1, $t1, -1
	sw	$t1, 0x10($t0)
	bnez	$t1, event_loop
	 nop

	la	$a0, debug_offset_text
	jal	puts
	 nop
	lui	$t0, 0x8006
	lw	$a0, 0x18($t0)
	jal	print_hex32
	 nop
	la	$a0, debug_available_text
	jal	puts
	 nop
	lui	$t0, 0x8006
	lw	$a0, 0x20($t0)
	jal	print_hex32
	 nop
	la	$a0, debug_end_text
	jal	puts
	 nop

	/* Real producer state proves all events took the callback->producer path. */
	lui	$t0, 0x8006
	lw	$t1, 0x18($t0)
	li	$t2, 0x3780
	bne	$t1, $t2, fail_write_offset
	 nop
	lw	$t1, 0x20($t0)
	bne	$t1, $t2, fail_available
	 nop
	lbu	$t1, 0x24($t0)
	li	$t2, 1
	bne	$t1, $t2, fail_ready
	 nop

	/* The DMA setup is a real firmware write to relocated RAM-backed registers. */
	lui	$t0, 0x8006
	ori	$t0, $t0, 0x4000
	lw	$t1, 0x40($t0)
	li	$t2, 0x4b03
	bne	$t1, $t2, fail_dma_control
	 nop
	lw	$t1, 0x44($t0)
	li	$t2, 0x80061000
	bne	$t1, $t2, fail_dma_address
	 nop
	lw	$t1, 0x54($t0)
	li	$t2, 0x3720
	bne	$t1, $t2, fail_dma_size
	 nop

	/* Producer DMA ring is distinct from the firmware staging/consumer buffer. */
	lui	$t0, 0x8006
	ori	$t0, $t0, 0x1000
	lbu	$t1, 0($t0)
	li	$t2, 0x5a
	bne	$t1, $t2, fail_producer_data
	 nop
	li	$t3, 0x377f
addu	$t0, $t0, $t3
	lbu	$t1, 0($t0)
	bne	$t1, $t2, fail_producer_data
	 nop
	la	$a0, pass_text
	jal	puts
	 nop
	b	halt
	 nop

fail_write_offset:
	la	$a0, write_offset_text
	b	fail
	 nop
fail_available:
	la	$a0, available_text
	b	fail
	 nop
fail_ready:
	la	$a0, ready_text
	b	fail
	 nop
fail_dma_control:
	la	$a0, dma_control_text
	b	fail
	 nop
fail_dma_address:
	la	$a0, dma_address_text
	b	fail
	 nop
fail_dma_size:
	la	$a0, dma_size_text
	b	fail
	 nop
fail_producer_data:
	la	$a0, producer_data_text
	b	fail
	 nop
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

	.ent	print_hex32
print_hex32:
	or	$t5, $a0, $zero
	li	$t6, 28
hex32_loop:
	srlv	$t0, $t5, $t6
	andi	$t0, $t0, 0x0f
	li	$t1, 10
	slt	$t2, $t0, $t1
	bnez	$t2, hex32_digit
	 nop
	addiu	$t0, $t0, 0x37
	b	hex32_emit
	 nop
hex32_digit:
	addiu	$t0, $t0, 0x30
hex32_emit:
	lui	$t1, 0xbf00
	ori	$t1, $t1, 0x0900
	sb	$t0, 0($t1)
	addiu	$t6, $t6, -4
	bgez	$t6, hex32_loop
	 nop
	jr	$ra
	 nop
	.end	print_hex32

	/* Driver callback/MMIO helper pointer targets are explicit no-op shims. */
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
	.asciz	"REAL USOUND EVENT-DMA PASS callback=74 producer_bytes=0x3780 dma_threshold=0x3720 dma_ring=0x80061000 staging_consumer_ring=0x80068000 mmio=ram service_and_dma=unmodeled\r\n"
debug_offset_text:
	.asciz	"write_offset=0x"
debug_available_text:
	.asciz	" available=0x"
debug_end_text:
	.asciz	"\r\n"
write_offset_text:
	.asciz	"REAL USOUND EVENT-DMA FAIL write-offset\r\n"
available_text:
	.asciz	"REAL USOUND EVENT-DMA FAIL available-count\r\n"
ready_text:
	.asciz	"REAL USOUND EVENT-DMA FAIL ready-latch\r\n"
dma_control_text:
	.asciz	"REAL USOUND EVENT-DMA FAIL dma-control\r\n"
dma_address_text:
	.asciz	"REAL USOUND EVENT-DMA FAIL dma-address\r\n"
dma_size_text:
	.asciz	"REAL USOUND EVENT-DMA FAIL dma-size\r\n"
producer_data_text:
	.asciz	"REAL USOUND EVENT-DMA FAIL producer-ring-data\r\n"

	.section .driver,"ax"
	.align	2
	.incbin	"usound_code.bin"
