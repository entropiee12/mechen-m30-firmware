/*
 * Freestanding QEMU/Malta probe for the recovered USB Audio boundary.
 *
 * This deliberately does not call M30 firmware.  It executes the recovered
 * packet/ring contract as guest MIPS code so the boundary is tested under a
 * real QEMU CPU and memory model without inventing an ATJ2127 peripheral.
 */
	.set	mips32
	.set	noreorder
	.set	noat

	.equ	UART_BASE,      0xbf000900
	.equ	UART_THR,       0
	.equ	UART_LSR,       5
	.equ	UART_LSR_THRE,  0x20
	.equ	RING_BASE,      0x80030000
	.equ	RING_CAPACITY,  0x1000
	.equ	PACKET_BYTES,   200
	.equ	PACKET_COUNT,   32
	.equ	BATCH_COUNT,    16

	.text
	.globl	_start
	.ent	_start
_start:
	/* Keep the stack away from the deterministic ring and status areas. */
	lui	$sp, 0x8004
	ori	$sp, $sp, 0xfff0

	/* t0 = write offset, t1 = available bytes, t2 = packet index. */	or	$t0, $zero, $zero
	or	$t1, $zero, $zero
	or	$t2, $zero, $zero
	/* t3 is the persistent consumer/read offset across batches. */	or	$t3, $zero, $zero
	lui	$s3, 0x8003

submit_packet:
	/* A full 200-byte packet is the recovered 2ch/16-bit/48 kHz frame. */	li	$s2, PACKET_BYTES
	subu	$t4, $zero, $t1
	addiu	$t4, $t4, RING_CAPACITY
	slt	$t5, $t4, $s2
	bnez	$t5, fail_overrun
	 nop

	/* t6 = packet byte index; t7 = packet*200 + byte index modulo 256. */	or	$t6, $zero, $zero
	li	$t7, PACKET_BYTES
	multu	$t2, $t7
	mflo	$t7

write_packet:
	addu	$t8, $t0, $t6
	andi	$t9, $t8, RING_CAPACITY - 1
	addu	$t9, $t9, $s3
	addu	$s4, $t7, $t6
	andi	$s4, $s4, 0xff
	sb	$s4, 0($t9)
	addiu	$t6, $t6, 1
	slti	$s4, $t6, PACKET_BYTES
	bnez	$s4, write_packet
	 nop

	addiu	$t0, $t0, PACKET_BYTES
	andi	$t0, $t0, RING_CAPACITY - 1
	addiu	$t1, $t1, PACKET_BYTES
	addiu	$t2, $t2, 1

	/* Consume a batch before queueing the next one; batch two crosses wrap. */	andi	$s1, $t2, BATCH_COUNT - 1
	bnez	$s1, maybe_submit
	 nop

	/* t3 = persistent read offset, t4 = first packet in this batch. */
	subu	$t4, $t2, BATCH_COUNT
	li	$s0, BATCH_COUNT

consume_batch:
	li	$t5, 0
	li	$t6, PACKET_BYTES
	multu	$t4, $t6
	mflo	$t6

verify_packet:
	addu	$t7, $t3, $t5
	andi	$t8, $t7, RING_CAPACITY - 1
	addu	$t8, $t8, $s3
	lbu	$t9, 0($t8)
	addu	$s4, $t6, $t5
	andi	$s4, $s4, 0xff
	bne	$t9, $s4, fail_payload
	 nop
	addiu	$t5, $t5, 1
	slti	$s4, $t5, PACKET_BYTES
	bnez	$s4, verify_packet
	 nop

	addiu	$t3, $t3, PACKET_BYTES
	andi	$t3, $t3, RING_CAPACITY - 1
	addiu	$t4, $t4, 1
	addiu	$t1, $t1, -PACKET_BYTES
	addiu	$s0, $s0, -1
	bnez	$s0, consume_batch
	 nop

maybe_submit:
	slti	$s4, $t2, PACKET_COUNT
	bnez	$s4, submit_packet
	 nop

	/* All 32 packets crossed the ring boundary and round-tripped intact. */	la	$a0, pass_text
	jal	puts
	 nop
	li	$v0, 0
	b	halt
	 nop

fail_overrun:
	la	$a0, overrun_text
	b	fail
	 nop
fail_payload:
	la	$a0, payload_text

fail:
	jal	puts
	 nop
	li	$v0, 1

halt:
	/* The wrapper enforces a timeout and captures the UART line. */	1:
	b	1b
	 nop
	.end	_start

	.text
	.ent	puts
puts:
	addu	$t0, $a0, $zero
putc:
	lui	$t1, 0xbf00
	ori	$t1, $t1, 0x0900
	lbu	$t3, 0($t0)
	beqz	$t3, puts_done
	 nop
	sb	$t3, UART_THR($t1)
	addiu	$t0, $t0, 1
	b	putc
	 nop
puts_done:
	jr	$ra
	 nop
	.end	puts

	.section .rodata
pass_text:
	.asciz	"USB AUDIO QEMU BOUNDARY PASS packets=32 ring-wrap=1 pcm=2ch/16bit/48kHz\r\n"
overrun_text:
	.asciz	"USB AUDIO QEMU BOUNDARY FAIL ring-overrun\r\n"
payload_text:
	.asciz	"USB AUDIO QEMU BOUNDARY FAIL payload-mismatch\r\n"
