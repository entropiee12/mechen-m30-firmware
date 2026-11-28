/* Execute producer/consumer control paths with an explicit synthetic ring alias. */
	.set	mips32
	.set	noreorder
	.set	noat

	.section .vectors,"ax"
	.globl	general_exception
	.ent	general_exception
general_exception:
	/* The real consumer calls module 0x10 through a break-0 envelope. */
	lui	$k0, 0x8006
	ori	$k0, $k0, 0x4000
	lw	$k1, 0($k0)
	addiu	$k1, $k1, 1
	sw	$k1, 0($k0)
	lw	$k1, 4($k0)
	addu	$k1, $k1, $a2
	sw	$k1, 4($k0)
	sw	$v1, 8($k0)
	/* Confirm module 0x10 receives the expected ring source and 16 bytes. */
	lui	$k0, 0x8006
	ori	$k0, $k0, 0x400c
	sw	$zero, 0($k0)
	lui	$k1, 0x8006
	ori	$k1, $k1, 0x1000
	bne	$a1, $k1, callback_bad_data
	 nop
	li	$k1, 16
	bne	$a2, $k1, callback_bad_data
	 nop
	lui	$k1, 0x8006
	ori	$k1, $k1, 0x5000
	bne	$a0, $k1, callback_bad_data
	 nop
	bnez	$a3, callback_bad_data
	 nop
	lw	$k1, 0($a1)
	lui	$k0, 0x1312
	ori	$k0, $k0, 0x1110
	bne	$k1, $k0, callback_bad_data
	 nop
	lw	$k1, 4($a1)
	lui	$k0, 0x1716
	ori	$k0, $k0, 0x1514
	bne	$k1, $k0, callback_bad_data
	 nop
	lw	$k1, 8($a1)
	lui	$k0, 0x1b1a
	ori	$k0, $k0, 0x1918
	bne	$k1, $k0, callback_bad_data
	 nop
	lw	$k1, 12($a1)
	lui	$k0, 0x1f1e
	ori	$k0, $k0, 0x1d1c
	bne	$k1, $k0, callback_bad_data
	 nop
	/* Candidate module-0x10 memcpy(a0=dst,a1=src,a2=len,a3=0) shim.
	 * The same tuple shape is observed in the independent aud_dev probe. */
	lui	$k0, 0x8006
	ori	$k0, $k0, 0x4010
	sw	$t0, 0($k0)
	or	$k0, $a0, $zero
	or	$k1, $a1, $zero
	or	$t0, $a2, $zero
callback_copy_loop:
	beqz	$t0, callback_copy_done
	 nop
	lbu	$at, 0($k1)
	sb	$at, 0($k0)
	addiu	$k0, $k0, 1
	addiu	$k1, $k1, 1
	addiu	$t0, $t0, -1
	b	callback_copy_loop
	 nop
callback_copy_done:
	lui	$k0, 0x8006
	ori	$k0, $k0, 0x4010
	lw	$t0, 0($k0)
	b	callback_data_checked
	 nop
callback_bad_data:
	lui	$k0, 0x8006
	ori	$k0, $k0, 0x400c
	li	$k1, 1
	sw	$k1, 0($k0)
callback_data_checked:
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

	/* Guest RAM exception vector for the consumer's real callback. */
	lui	$k0, 0x8001
	ori	$k0, $k0, 0x1000
	mtc0	$k0, $15, 1
	nop
	mfc0	$k1, $12
	li	$t1, 0xffbfffff
	and	$k1, $k1, $t1
	mtc0	$k1, $12
	nop

	/* Producer state at 0x80060000; 0x6e40 ring aliases consumer ring at 0x80061000. */	lui	$t0, 0x8006
	sw	$zero, 0($t0)
	sw	$zero, 4($t0)
	sb	$zero, 0x10($t0)
	sb	$zero, 0x24($t0)
	sb	$zero, 0x28($t0)
	sw	$zero, 0x18($t0)
	sw	$zero, 0x20($t0)
	li	$t1, 16
	sb	$t1, 8($t0)
	sb	$zero, 9($t0)
	/* Seed the producer's 4 KiB scratch with a distinctive 16-byte input. */
	lui	$t0, 0x8006
	ori	$t0, $t0, 0x2000
	lui	$t1, 0x1312
	ori	$t1, $t1, 0x1110
	sw	$t1, 0($t0)
	lui	$t1, 0x1716
	ori	$t1, $t1, 0x1514
	sw	$t1, 4($t0)
	lui	$t1, 0x1b1a
	ori	$t1, $t1, 0x1918
	sw	$t1, 8($t0)
	lui	$t1, 0x1f1e
	ori	$t1, $t1, 0x1d1c
	sw	$t1, 12($t0)
	lui	$t0, 0x8006
	jalx	0x80050dc4
	 nop

	/* Producer must publish exactly the 16 bytes consumed below. */	lw	$t1, 0x18($t0)
	li	$t2, 16
	bne	$t1, $t2, fail_producer
	 nop
	lw	$t1, 0x20($t0)
	bne	$t1, $t2, fail_producer
	 nop

	/* Verify the real copy helper published the seeded bytes into the ring. */
	lui	$t0, 0x8006
	ori	$t0, $t0, 0x1000
	lw	$t1, 0($t0)
	lui	$t2, 0x1312
	ori	$t2, $t2, 0x1110
	bne	$t1, $t2, fail_producer_bytes
	 nop
	lw	$t1, 4($t0)
	lui	$t2, 0x1716
	ori	$t2, $t2, 0x1514
	bne	$t1, $t2, fail_producer_bytes
	 nop
	lw	$t1, 8($t0)
	lui	$t2, 0x1b1a
	ori	$t2, $t2, 0x1918
	bne	$t1, $t2, fail_producer_bytes
	 nop
	lw	$t1, 12($t0)
	lui	$t2, 0x1f1e
	ori	$t2, $t2, 0x1d1c
	bne	$t1, $t2, fail_producer_bytes
	 nop

	/* Consumer reads the synthetic alias and validates bytes at its callback source. */	lui	$t0, 0x8006
	li	$t2, 16
	ori	$t0, $t0, 0x3000
	sw	$t2, 0($t0)
	sw	$zero, 4($t0)
	sw	$zero, 8($t0)
	sw	$zero, 0xc($t0)
	lui	$t1, 0x8006
	ori	$t1, $t1, 0x4000
	sw	$zero, 0($t1)
	sw	$zero, 4($t1)
	sw	$zero, 8($t1)
	sw	$zero, 0xc($t1)
	/* a1 is the destination passed through the real callback envelope. */	lui	$a1, 0x8006
	ori	$a1, $a1, 0x5000
	/* Start with a sentinel so a successful candidate copy is observable. */
	li	$t1, 0xa5
	li	$t2, 16
init_destination:
	sb	$t1, 0($a1)
	addiu	$a1, $a1, 1
	addiu	$t2, $t2, -1
	bnez	$t2, init_destination
	 nop
	lui	$a1, 0x8006
	ori	$a1, $a1, 0x5000
	/* a2 is the consumer's available/read state descriptor. */	or	$a2, $t0, $zero
	jalx	0x80051654
	 nop

	li	$t2, 16
	bne	$v0, $t2, fail_consumer
	 nop
	lw	$t1, 0($t0)
	bnez	$t1, fail_consumer
	 nop
	lw	$t1, 8($t0)
	bne	$t1, $t2, fail_consumer
	 nop
	lui	$t0, 0x8006
	ori	$t0, $t0, 0x4000
	lw	$t1, 0($t0)
	li	$t2, 1
	bne	$t1, $t2, fail_callback_count
	 nop
	lw	$t1, 8($t0)
	li	$t2, 0x10
	bne	$t1, $t2, fail_callback_module_id
	 nop
	lw	$t1, 0xc($t0)
	bnez	$t1, fail_callback_data
	 nop
	/* Verify that the explicit candidate service shim copied the 16 bytes. */
	lui	$t0, 0x8006
	ori	$t0, $t0, 0x5000
	li	$t1, 0x10
	li	$t2, 16
verify_destination:
	lbu	$t3, 0($t0)
	bne	$t3, $t1, fail_destination_copy
	 nop
	addiu	$t0, $t0, 1
	addiu	$t1, $t1, 1
	addiu	$t2, $t2, -1
	bnez	$t2, verify_destination
	 nop

	la	$a0, pass_text
	jal	puts
	 nop
	b	halt
	 nop

fail_producer:
	la	$a0, producer_text
	b	fail
	 nop
fail_producer_bytes:
	la	$a0, producer_bytes_text
	b	fail
	 nop
fail_consumer:
	la	$a0, consumer_text
	b	fail
	 nop
fail_callback_count:
	la	$a0, callback_text
	b	fail
	 nop

fail_callback_module_id:
	la	$a0, module_id_text
	b	fail
	 nop
fail_callback_data:
	la	$a0, callback_data_text
	b	fail
	 nop
fail_destination_copy:
	la	$a0, destination_copy_text

fail:
	jal	puts
	 nop

halt:
	1:
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
	.asciz	"REAL USOUND CHAIN PASS alias=0x80061000 producer=16 consumer=16 source_bytes=16 module10_candidate_memcpy=1 destination_verified=1 module=0x10 service_is_hypothesis\r\n"
producer_text:
	.asciz	"REAL USOUND CHAIN FAIL producer\r\n"
producer_bytes_text:
	.asciz	"REAL USOUND CHAIN FAIL producer-bytes\r\n"
consumer_text:
	.asciz	"REAL USOUND CHAIN FAIL consumer\r\n"
callback_text:
	.asciz	"REAL USOUND CHAIN FAIL callback\r\n"
module_id_text:
	.asciz	"REAL USOUND CHAIN FAIL module-id\r\n"
callback_data_text:
	.asciz	"REAL USOUND CHAIN FAIL callback-source\r\n"
destination_copy_text:
	.asciz	"REAL USOUND CHAIN FAIL destination-copy\r\n"

	.section .driver,"ax"
	.align	2
	.incbin	"usound_code.bin"
