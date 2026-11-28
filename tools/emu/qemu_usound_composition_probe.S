	/* Probe a candidate producer/consumer composition using firmware addresses. */
	.set	mips32
	.set	noreorder
	.set	noat

	.section .vectors,"ax"
	.globl	general_exception
	.ent	general_exception
general_exception:
	/* Observe module-0x10 arguments; the fixed-ROM service effect stays opaque. */
	lui	$k0, 0x8006
	ori	$k0, $k0, 0x4000
	lw	$k1, 0($k0)
	addiu	$k1, $k1, 1
	sw	$k1, 0($k0)
	sw	$a0, 4($k0)
	sw	$a1, 8($k0)
	sw	$a2, 0xc($k0)
	sw	$a3, 0x10($k0)

	/* Validate callback args and the naturally advanced consumer source. */
	lw	$t5, 0x2c($k0)
	bne	$a0, $t5, exception_bad
	 nop
	lw	$k1, 0x28($k0)
	bne	$a1, $k1, exception_bad
	 nop
	li	$k1, 192
	bne	$a2, $k1, exception_bad
	 nop
	bnez	$a3, exception_bad
	 nop

	/* Validate every source byte against the current packet's expected pattern. */
	lw	$t6, 0x24($k0)
	or	$t7, $a1, $zero
	li	$t9, 192
exception_source_check:
	lbu	$t0, 0($t7)
	bne	$t0, $t6, exception_source_bad
	 nop
	addiu	$t7, $t7, 1
	addiu	$t9, $t9, -1
	bnez	$t9, exception_source_check
	 nop
	b	exception_return
	 nop
exception_source_bad:
	sw	$t0, 0x30($k0)	/* actual byte */
	sw	$t7, 0x34($k0)	/* source address */
	sw	$t6, 0x38($k0)	/* expected byte */
exception_bad:
	li	$k1, 1
	sw	$k1, 0x14($k0)
	/* Keep returning so the guest can report a concrete mismatch. */
exception_return:
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
	/* Use a RAM-backed vector; fixed-ROM behavior is outside this probe. */
	lui	$k0, 0x8001
	ori	$k0, $k0, 0x1000
	mtc0	$k0, $15, 1
	 nop
	mfc0	$k1, $12
	li	$t1, 0xffbfffff
	and	$k1, $k1, $t1
	mtc0	$k1, $12
	 nop

	/* Shared firmware state: write, read, available, reset/short-read. */
	lui	$t0, 0x8006
	sw	$zero, 0($t0)
	sw	$zero, 4($t0)
	sw	$zero, 0x18($t0)
	sw	$zero, 0x1c($t0)
	sw	$zero, 0x20($t0)
	sb	$zero, 0x24($t0)
	sw	$zero, 0x28($t0)
	sw	$zero, 0x2c($t0)
	lui	$t0, 0x8006
	ori	$t0, $t0, 0x4000
	sw	$zero, 0($t0)
	sw	$zero, 4($t0)
	sw	$zero, 8($t0)
	sw	$zero, 0xc($t0)
	sw	$zero, 0x10($t0)
	sw	$zero, 0x14($t0)
	sw	$zero, 0x30($t0)
	sw	$zero, 0x34($t0)
	sw	$zero, 0x38($t0)
	lui	$t0, 0x8006
	ori	$t0, $t0, 0x2000
	sb	$zero, 0x20($t0)	/* direct format */

	/* The real shared 0x9fc34000 area is staging and consumer ring. */
	lui	$t0, 0x8006
	ori	$t0, $t0, 0x8000
	li	$t1, 0x1000
	li	$t2, 0x11111111
seed_shared_buffer:
	sw	$t2, 0($t0)
	addiu	$t0, $t0, 4
	addiu	$t1, $t1, -4
	bnez	$t1, seed_shared_buffer
	 nop

	/* Descriptor asks the actual consumer for one packet (192 bytes). */
	lui	$t0, 0x8006
	ori	$t0, $t0, 0x3000
	li	$t1, 192
	sw	$t1, 0($t0)
	sw	$zero, 4($t0)
	sw	$zero, 8($t0)
	sw	$zero, 0xc($t0)

	/* First packet A: producer and consumer use their unmodified shared offsets. */
	lui	$t0, 0x8006
	ori	$t0, $t0, 0x6700
	li	$t1, 0x11111111
	sw	$t1, 0xc($t0)	/* synthetic FIFO word consumed by the real port helper */
	li	$a0, 0x11
	li	$a1, 0
	jal	producer_event
	 nop
	li	$t1, 192
	bne	$v0, $t1, fail_first_count
	 nop
	lui	$t0, 0x8006
	lw	$t1, 0x1c($t0)
	li	$t2, 192
	bne	$t1, $t2, fail_first_read_offset
	 nop
	lw	$t1, 0x20($t0)
bnez	$t1, fail_first_available
	 nop
	ori	$t0, $t0, 0x4000
	lw	$t1, 0x14($t0)
	bnez	$t1, fail_exception
	 nop

	/* Second packet B is read through the real word-port helper. */
	lui	$t0, 0x8006
	ori	$t0, $t0, 0x6700
	li	$t1, 0x22222222
	sw	$t1, 0xc($t0)
	li	$a0, 0x22
	li	$a1, 1
	jal	producer_event
	 nop
	li	$t1, 192
	bne	$v0, $t1, fail_second_count
	 nop
	lui	$t0, 0x8006
	lw	$t1, 0x1c($t0)
	li	$t2, 384
	bne	$t1, $t2, fail_second_read_offset
	 nop
	lw	$t1, 0x20($t0)
bnez	$t1, fail_second_available
	 nop
	ori	$t0, $t0, 0x4000
	lw	$t1, 0x14($t0)
	beqz	$t1, fail_no_divergence
	 nop
	lw	$t1, 0x30($t0)
	li	$t2, 0x11
	bne	$t1, $t2, fail_unexpected_byte
	 nop
	lw	$t1, 0x34($t0)
	li	$t2, 0x800680c0
	bne	$t1, $t2, fail_unexpected_address
	 nop
	lw	$t1, 0x38($t0)
	li	$t2, 0x22
	bne	$t1, $t2, fail_unexpected_byte
	 nop
	/* Confirm the whole second source remained at the initial 0x11 fill. */
	li	$t0, 0x800680c0
	li	$t1, 192
	li	$t2, 0x11
verify_stale_source:
	lbu	$t3, 0($t0)
	bne	$t3, $t2, fail_unexpected_byte
	 nop
	addiu	$t0, $t0, 1
	addiu	$t1, $t1, -1
	bnez	$t1, verify_stale_source
	 nop
	/* The producer replaced staging slot zero with B, while +0xc0 stayed A. */
	li	$t0, 0x80068000
	lbu	$t1, 0($t0)
	li	$t2, 0x22
	bne	$t1, $t2, fail_staging_base
	 nop

	/* Check the first bytes in the separate DMA ring. */
	lui	$t0, 0x8006
	ori	$t0, $t0, 0x1000
	lbu	$t1, 0($t0)
	li	$t2, 0x11
	bne	$t1, $t2, fail_dma_a
	 nop
	lbu	$t1, 0xc0($t0)
	li	$t2, 0x22
	bne	$t1, $t2, fail_dma_b
	 nop
	lui	$t0, 0x8006
	ori	$t0, $t0, 0x1000
	li	$t1, 192
	li	$t2, 0x11
verify_dma_a:
	lbu	$t3, 0($t0)
	bne	$t3, $t2, fail_dma_a
	 nop
	addiu	$t0, $t0, 1
	addiu	$t1, $t1, -1
	bnez	$t1, verify_dma_a
	 nop
	lui	$t0, 0x8006
	ori	$t0, $t0, 0x10c0
	li	$t1, 192
	li	$t2, 0x22
verify_dma_b:
	lbu	$t3, 0($t0)
	bne	$t3, $t2, fail_dma_b
	 nop
	addiu	$t0, $t0, 1
	addiu	$t1, $t1, -1
	bnez	$t1, verify_dma_b
	 nop
	lui	$t0, 0x8006
	ori	$t0, $t0, 0x4000
	lw	$t1, 0($t0)
	li	$t2, 2
	bne	$t1, $t2, fail_call_count
	 nop
	lw	$t1, 8($t0)
	li	$t2, 0x800680c0
	bne	$t1, $t2, fail_second_source
	 nop
	lw	$t1, 0xc($t0)
	li	$t2, 192
	bne	$t1, $t2, fail_second_length
	 nop

	la	$a0, observation_text
	jal	puts
	 nop
	b	halt
	 nop

/* Run callback -> producer, then explicitly invoke consumer; a0=byte, a1=index. */
producer_event:
	addiu	$sp, $sp, -24
	sw	$ra, 20($sp)
	sw	$a0, 16($sp)
	sw	$a1, 12($sp)
	/* Record the expected destination argument, without modeling its ROM effect. */
	lui	$t0, 0x8006
	ori	$t0, $t0, 0x4000
	li	$t1, 0x8006a000
	lw	$t2, 12($sp)
	beqz	$t2, producer_expected_a
	 nop
	addiu	$t1, $t1, 256
	li	$t3, 0x22
	b	producer_expected_set
	 nop
producer_expected_a:
	li	$t3, 0x11
producer_expected_set:
	li	$t4, 0x80068000
	lw	$t2, 12($sp)
	beqz	$t2, producer_source_set
	 nop
	addiu	$t4, $t4, 192
producer_source_set:
	sw	$t4, 0x28($t0)
	sw	$t1, 0x2c($t0)
	sw	$t3, 0x24($t0)

	/* M30 callback event and 192-byte USB length, all RAM-backed. */
	lui	$t0, 0x8006
	ori	$t0, $t0, 0x6000
	li	$t1, 2
	sb	$t1, 0($t0)
	lui	$t0, 0x8006
	sw	$zero, 0($t0)	/* producer status cleared per packet */
	li	$t1, 0xc0
	sb	$t1, 8($t0)
	sb	$zero, 9($t0)
	jalx	0x8fc31114
	 nop

	/* Synthetic call edge: this harness does not establish a firmware call edge. */
	lui	$a0, 0
	lw	$t0, 12($sp)
	lui	$a1, 0x8006
	ori	$a1, $a1, 0xa000
	beqz	$t0, producer_consumer_dest_ready
	 nop
	addiu	$a1, $a1, 0x100
producer_consumer_dest_ready:
	lui	$a2, 0x8006
	ori	$a2, $a2, 0x3000
	jalx	0x8fc31654
	 nop
	lw	$ra, 20($sp)
	addiu	$sp, $sp, 24
	jr	$ra
	 nop

fail_first_count:
	la	$a0, first_count_text
	b	fail
	 nop
fail_first_read_offset:
	la	$a0, first_read_text
	b	fail
	 nop
fail_first_available:
	la	$a0, first_available_text
	b	fail
	 nop
fail_second_count:
	la	$a0, second_count_text
	b	fail
	 nop
fail_second_read_offset:
	la	$a0, second_read_text
	b	fail
	 nop
fail_second_available:
	la	$a0, second_available_text
	b	fail
	 nop
fail_exception:
	la	$a0, exception_text
	b	fail
	 nop
fail_call_count:
	la	$a0, call_count_text
	b	fail
	 nop
fail_second_source:
	la	$a0, second_source_text
	b	fail
	 nop
fail_second_length:
	la	$a0, second_length_text
	b	fail
	 nop

fail_no_divergence:
	la	$a0, no_divergence_text
	b	fail
	 nop
fail_unexpected_byte:
	la	$a0, unexpected_byte_text
	b	fail
	 nop
fail_unexpected_address:
	la	$a0, unexpected_address_text
	b	fail
	 nop
fail_staging_base:
	la	$a0, staging_base_text
	b	fail
	 nop
fail_dma_a:
	la	$a0, dma_a_text
	b	fail
	 nop
fail_dma_b:
	la	$a0, dma_b_text
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

	.section .callback,"ax"
	.set	mips16
noop_callback:
	jr	$ra
	 nop
	.set	mips32

	.section .rodata
observation_text:
	.asciz	"SYNTHETIC USOUND COMPOSITION MISMATCH callbacks=2 real_port_helper=1 DMA_ring=A,B[192+192] staging[0]=B consumer_source[0xc0]=stale-0x11[192] consumer_call_edge=harness module10=observed-only FIFO=ram-backed-word-port\r\n"
first_count_text:
	.asciz	"SYNTHETIC USOUND COMPOSITION FAIL first-consumed-count\r\n"
first_read_text:
	.asciz	"SYNTHETIC USOUND COMPOSITION FAIL first-read-offset\r\n"
first_available_text:
	.asciz	"SYNTHETIC USOUND COMPOSITION FAIL first-available-count\r\n"
second_count_text:
	.asciz	"SYNTHETIC USOUND COMPOSITION FAIL second-consumed-count\r\n"
second_read_text:
	.asciz	"SYNTHETIC USOUND COMPOSITION FAIL second-read-offset\r\n"
second_available_text:
	.asciz	"SYNTHETIC USOUND COMPOSITION FAIL second-available-count\r\n"
exception_text:
	.asciz	"SYNTHETIC USOUND COMPOSITION FAIL module10-arguments-or-source\r\n"
dma_a_text:
	.asciz	"SYNTHETIC USOUND COMPOSITION FAIL dma-ring-first-packet\r\n"
dma_b_text:
	.asciz	"SYNTHETIC USOUND COMPOSITION FAIL dma-ring-second-packet\r\n"
call_count_text:
	.asciz	"SYNTHETIC USOUND COMPOSITION FAIL module10-call-count\r\n"
second_source_text:
	.asciz	"SYNTHETIC USOUND COMPOSITION FAIL second-consumer-source\r\n"
second_length_text:
	.asciz	"SYNTHETIC USOUND COMPOSITION FAIL second-consumer-length\r\n"
no_divergence_text:
	.asciz	"SYNTHETIC USOUND COMPOSITION FAIL expected-stale-source-not-observed\r\n"
unexpected_byte_text:
	.asciz	"SYNTHETIC USOUND COMPOSITION FAIL unexpected-source-byte\r\n"
unexpected_address_text:
	.asciz	"SYNTHETIC USOUND COMPOSITION FAIL unexpected-source-address\r\n"
staging_base_text:
	.asciz	"SYNTHETIC USOUND COMPOSITION FAIL second-event-did-not-replace-staging-base\r\n"

	.section .driver,"ax"
	.align	2
	.incbin	"usound_code.bin"
