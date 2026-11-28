.set mips32
.set noreorder
.set noat
.text
.globl _start
.ent _start
_start:
	lui $sp, 0x8004
	ori $sp, $sp, 0xfff0

	/* SET_INTERFACE alt=1: seed ring state and submit an 8-byte setup packet. */
	li $t0, 0x800601e8
	li $t1, 0x41424344
	sw $t1, 0($t0)
	li $t0, 0x800601ec
	li $t1, 0x51525354
	sw $t1, 0($t0)
	li $t0, 0x800601f0
	li $t1, 0x61626364
	sw $t1, 0($t0)
	li $t0, 0x80060213
	li $t1, 0x71
	sb $t1, 0($t0)
	li $t0, 0x80063002
	sb $zero, 0($t0)
	li $t0, 0x80063180
	li $t1, 0x01
	sb $t1, 0($t0)
	li $t1, 0x0b
	sb $t1, 1($t0)
	li $t1, 0x01
	sb $t1, 2($t0)
	sb $zero, 3($t0)
	li $t1, 0x01
	sb $t1, 4($t0)
	sb $zero, 5($t0)
	sb $zero, 6($t0)
	sb $zero, 7($t0)
	jalx 0x8fc303c4
	 nop

	li $t0, 0x80063002
	lbu $t1, 0($t0)
	andi $t1, $t1, 2
	beqz $t1, fail_alt1_ack
	 nop
	li $t0, 0x80060214
	lw $t1, 0($t0)
	li $t2, 0x00010b01
	bne $t1, $t2, fail_setup_copy
	 nop
	lw $t1, 4($t0)
	li $t2, 1
	bne $t1, $t2, fail_setup_copy
	 nop
	li $t0, 0x800601e8
	lw $t1, 0($t0)
	bnez $t1, fail_alt1_state
	 nop
	li $t0, 0x800601ec
	lw $t1, 0($t0)
	bnez $t1, fail_alt1_state
	 nop
	li $t0, 0x800601f0
	lw $t1, 0($t0)
	bnez $t1, fail_alt1_state
	 nop
	li $t0, 0x80060213
	lbu $t1, 0($t0)
	bnez $t1, fail_alt1_state
	 nop

	/* Fill a 192-byte audio packet, then enqueue 74 packets across the threshold. */
	li $t0, 0x80060000
	sw $zero, 0($t0)
	sw $zero, 4($t0)
	li $t0, 0x80067020
	sb $zero, 0($t0)
	li $t0, 0x80062000
	li $t2, 0
fill_packet:
	sb $t2, 0($t0)
	addiu $t0, $t0, 1
	addiu $t2, $t2, 1
	li $t3, 192
	bne $t2, $t3, fill_packet
	 nop
	li $t0, 0x80060008
	li $t1, 0xc0
	sb $t1, 0($t0)
	sb $zero, 1($t0)
	li $t0, 0x8006f020
	li $t1, 74
	sw $t1, 0($t0)
producer_packet_loop:
	li $t0, 0x80060000
	sw $zero, 0($t0)
	jalx 0x8fc30dc4
	 nop
	li $t0, 0x8006f020
	lw $t1, 0($t0)
	addiu $t1, $t1, -1
	sw $t1, 0($t0)
	bnez $t1, producer_packet_loop
	 nop
	li $t0, 0x800601e8
	lw $t1, 0($t0)
	li $t2, 0x3780
	bne $t1, $t2, fail_producer_write
	 nop
	li $t0, 0x800601f0
	lw $t1, 0($t0)
	bne $t1, $t2, fail_producer_count
	 nop
	li $t0, 0x80060213
	lbu $t1, 0($t0)
	li $t2, 1
	bne $t1, $t2, fail_producer_ready
	 nop
	/* The threshold helper must program DMA before the separate setup check below. */
	li $t0, 0x80064044
	lw $t1, 0($t0)
	li $t2, 0x80080000
	bne $t1, $t2, fail_dma_setup
	 nop
	li $t0, 0x80064040
	lw $t1, 0($t0)
	li $t2, 0x4b03
	bne $t1, $t2, fail_dma_setup
	 nop
	li $t0, 0x80064054
	lw $t1, 0($t0)
	li $t2, 0x3720
	bne $t1, $t2, fail_dma_setup
	 nop
	/* Check every copied ring byte against the repeating staged packet. */
	li $t0, 0x80080000
	li $t1, 0
	li $t2, 0
	li $t5, 0x3780
verify_ring:
	lbu $t3, 0($t0)
	bne $t3, $t2, fail_producer_data
	 nop
	addiu $t0, $t0, 1
	addiu $t1, $t1, 1
	addiu $t2, $t2, 1
	li $t4, 192
	bne $t2, $t4, verify_ring_next
	 nop
	move $t2, $zero
verify_ring_next:
	bne $t1, $t5, verify_ring
	 nop

	/* DMA setup configures the same ring; no transfer is claimed here. */
	li $t0, 0x80067020
	sb $zero, 0($t0)
	li $t0, 0x80064000
	sw $zero, 0($t0)
	li $t0, 0x80065004
	sw $zero, 0($t0)
	li $t0, 0x80064040
	sw $zero, 0($t0)
	li $t0, 0x80064004
	sw $zero, 0($t0)
	li $t0, 0x80064008
	sw $zero, 0($t0)
	li $t0, 0x80064044
	sw $zero, 0($t0)
	li $t0, 0x80064054
	sw $zero, 0($t0)
	jalx 0x8fc312e0
	 nop
	li $t0, 0x80064044
	lw $t1, 0($t0)
	li $t2, 0x80080000
	bne $t1, $t2, fail_dma_setup
	 nop
	li $t0, 0x80064040
	lw $t1, 0($t0)
	li $t2, 0x4b03
	bne $t1, $t2, fail_dma_setup
	 nop
	li $t0, 0x80064054
	lw $t1, 0($t0)
	li $t2, 0x3720
	bne $t1, $t2, fail_dma_setup
	 nop

	/* Altsetting 0 must preserve the producer's live queue state. */
	li $t0, 0x80063002
	sb $zero, 0($t0)
	li $t0, 0x80063180
	li $t1, 0x01
	sb $t1, 0($t0)
	li $t1, 0x0b
	sb $t1, 1($t0)
	sb $zero, 2($t0)
	sb $zero, 3($t0)
	li $t1, 0x01
	sb $t1, 4($t0)
	sb $zero, 5($t0)
	sb $zero, 6($t0)
	sb $zero, 7($t0)
	jalx 0x8fc303c4
	 nop

	li $t0, 0x80063002
	lbu $t1, 0($t0)
	andi $t1, $t1, 2
	beqz $t1, fail_alt0_ack
	 nop
	li $t0, 0x80060214
	lw $t1, 0($t0)
	li $t2, 0x00000b01
	bne $t1, $t2, fail_setup_copy
	 nop
	lw $t1, 4($t0)
	li $t2, 1
	bne $t1, $t2, fail_setup_copy
	 nop
	li $t0, 0x800601e8
	lw $t1, 0($t0)
	li $t2, 0x3780
	bne $t1, $t2, fail_alt0_state
	 nop
	li $t0, 0x800601ec
	lw $t1, 0($t0)
	li $t2, 0
	bne $t1, $t2, fail_alt0_state
	 nop
	li $t0, 0x800601f0
	lw $t1, 0($t0)
	li $t2, 0x3780
	bne $t1, $t2, fail_alt0_state
	 nop
	li $t0, 0x80060213
	lbu $t1, 0($t0)
	li $t2, 1
	bne $t1, $t2, fail_alt0_state
	 nop

	/* One real DMA completion subtracts one threshold from the shared queue. */
	li $t0, 0x80064010
	li $t1, 0xa5
	sw $t1, 0($t0)
	jalx 0x8fc3125c
	 nop
	li $t0, 0x800601f0
	lw $t1, 0($t0)
	li $t2, 0x60
	bne $t1, $t2, fail_completion
	 nop
	li $t0, 0x80060213
	lbu $t1, 0($t0)
	li $t2, 1
	bne $t1, $t2, fail_completion
	 nop
	li $t0, 0x80064010
	lw $t1, 0($t0)
	li $t2, 0xa4
	bne $t1, $t2, fail_completion
	 nop

	la $a0, pass_text
	jal puts
	 nop
	b halt
	 nop
fail_alt1_ack:
	la $a0, fail_alt1_ack_text
	b fail
	 nop
fail_setup_copy:
	la $a0, fail_setup_text
	b fail
	 nop
fail_alt1_state:
	la $a0, fail_alt1_state_text
	b fail
	 nop
fail_alt0_ack:
	la $a0, fail_alt0_ack_text
	b fail
	 nop
fail_alt0_state:
	la $a0, fail_alt0_state_text
	b fail
	 nop
fail_completion:
	la $a0, fail_completion_text
	b fail
	 nop
fail_producer_write:
	move $s0, $t1
	la $a0, fail_producer_write_text
	jal puts
	 nop
	move $a0, $s0
	jal puthex
	 nop
	b halt
	 nop
	b fail
	 nop
fail_producer_count:
	la $a0, fail_producer_count_text
	b fail
	 nop
fail_producer_ready:
	la $a0, fail_producer_ready_text
	b fail
	 nop
fail_producer_data:
	la $a0, fail_producer_data_text
	b fail
	 nop
fail_dma_setup:
	la $a0, fail_dma_setup_text
fail:
	jal puts
	 nop
halt:
1:
	b 1b
	 nop
.end _start

.ent puts
puts:
	or $t0, $a0, $zero
putc:
	lui $t1, 0xbf00
	ori $t1, $t1, 0x0900
	lbu $t3, 0($t0)
	beqz $t3, puts_done
	 nop
	sb $t3, 0($t1)
	addiu $t0, $t0, 1
	b putc
	 nop
puts_done:
	jr $ra
	 nop
.end puts

.ent puthex
puthex:
	or $t0, $a0, $zero
	li $t4, 8
	lui $t5, 0xbf00
	ori $t5, $t5, 0x0900
puthex_loop:
	srl $t1, $t0, 28
	andi $t1, $t1, 0x0f
	sltiu $t2, $t1, 10
	bnez $t2, puthex_digit
	 nop
	addiu $t1, $t1, 55
	b puthex_emit
	 nop
puthex_digit:
	addiu $t1, $t1, 48
puthex_emit:
	sb $t1, 0($t5)
	sll $t0, $t0, 4
	addiu $t4, $t4, -1
	bnez $t4, puthex_loop
	 nop
	jr $ra
	 nop
.end puthex

.section .rodata
pass_text: .asciz "REAL USOUND EP0 STREAM PASS alt1-reset=1 producer=0x3780 packets=74 packet-bytes=192 threshold-cross=1 ready=1 completion-remains=0x60 ring-copy=all\r\n"
fail_alt1_ack_text: .asciz "REAL USOUND EP0 FAIL alt1-ack\r\n"
fail_setup_text: .asciz "REAL USOUND EP0 FAIL setup-copy\r\n"
fail_alt1_state_text: .asciz "REAL USOUND EP0 FAIL alt1-state\r\n"
fail_alt0_ack_text: .asciz "REAL USOUND EP0 FAIL alt0-ack\r\n"
fail_alt0_state_text: .asciz "REAL USOUND EP0 FAIL alt0-state\r\n"
fail_completion_text: .asciz "REAL USOUND EP0 STREAM FAIL completion-state\r\n"
fail_producer_write_text: .asciz "REAL USOUND EP0 STREAM FAIL producer-write-offset="
fail_producer_count_text: .asciz "REAL USOUND EP0 STREAM FAIL producer-byte-count\r\n"
fail_producer_ready_text: .asciz "REAL USOUND EP0 STREAM FAIL producer-ready-latch\r\n"
fail_producer_data_text: .asciz "REAL USOUND EP0 STREAM FAIL producer-ring-copy\r\n"
fail_dma_setup_text: .asciz "REAL USOUND EP0 STREAM FAIL dma-setup\r\n"

.section .callback,"ax"
.set mips16
.ent usound_callback_stub
usound_callback_stub:
	jr $ra
	 nop
.end usound_callback_stub
.set mips32

.section .driver,"ax"
.incbin "usound_code.bin"
