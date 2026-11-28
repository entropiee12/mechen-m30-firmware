/* Compare real usound producer/DMA behavior from seeded SET_CUR post-state.
 * The EP0 request handler is deliberately not called by this experiment.
 */
	.set mips32
	.set noreorder
	.set noat

	.text
	.globl _start
	.ent _start
_start:
	lui $sp, 0x8004
	ori $sp, $sp, 0xfff0

	/* Ghidra RAM 0x9fc33c00 is remapped as a separate guest RAM region. */
	li $t0, 0x80070000
	.if RATE_HZ == 48000
	li $t1, 0x00bb80
	.elseif RATE_HZ == 32000
	li $t1, 0x007d00
	.else
	.error "RATE_HZ must be 48000 or 32000"
	.endif
	sb $t1, 0($t0)
	srl $t2, $t1, 8
	sb $t2, 1($t0)
	srl $t2, $t1, 16
	sb $t2, 2($t0)
	li $t1, 1
	sb $t1, 0x1d0($t0)
	sb $t1, 0x1d7($t0)

	/* Producer MMIO status C008000B remains 0x80060000, separate from the
	 * offset-preserving SET_CUR data map at 0x80070000. */
	li $t0, 0x80060000
	sw $zero, 0($t0)           /* producer status */
	li $t0, 0x800701d8
	sw $zero, 0($t0)           /* stop flag: original 0x9fc33dd8 */
	li $t0, 0x800701e8
	sw $zero, 0($t0)           /* ring write offset: original +0x1e8 */
	li $t0, 0x800701f0
	sw $zero, 0($t0)           /* queued bytes: original +0x1f0 */
	li $t0, 0x80070213
	sb $zero, 0($t0)           /* ready latch: original +0x213 */
	li $t0, 0x80070220
	sb $zero, 0($t0)           /* format 0: direct 16-bit copy */

	/* Identical 192-byte ramp packet, submitted 74 times. */
	li $t0, 0x80072000
	move $t2, $zero
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
	la $a0, start_text
	jal puts
	 nop

packet_loop:
	li $t0, 0x80060000
	sw $zero, 0($t0)
	jalx 0x8fc30dc4
	 nop
	li $t0, 0x8006f020
	lw $t1, 0($t0)
	addiu $t1, $t1, -1
	sw $t1, 0($t0)
	bnez $t1, packet_loop
	 nop
	la $a0, producer_done_text
	jal puts
	 nop

	/* Assert producer count and ready state before observing DMA setup. */
	li $t0, 0x800701f0
	lw $t1, 0($t0)
	li $t2, 0x3780
	bne $t1, $t2, fail_count
	 nop
	li $t0, 0x80070213
	lbu $t1, 0($t0)
	li $t2, 1
	bne $t1, $t2, fail_ready
	 nop

	/* The threshold helper has run the real DMA setup path. */
	li $t0, 0x80064044
	lw $t1, 0($t0)
	li $t2, 0x80080000
	bne $t1, $t2, fail_dma
	 nop
	li $t0, 0x80064040
	lw $t1, 0($t0)
	li $t2, 0x4b03
	bne $t1, $t2, fail_dma
	 nop
	li $t0, 0x80064054
	lw $t1, 0($t0)
	li $t2, 0x3720
	bne $t1, $t2, fail_dma
	 nop

	/* Verify every ring byte and calculate a comparable payload checksum. */
	li $t0, 0x80080000
	move $t1, $zero
	move $t2, $zero
	move $s2, $zero
	li $t5, 0x3780
verify_ring:
	lbu $t3, 0($t0)
	bne $t3, $t2, fail_payload
	 nop
	addu $s2, $s2, $t3
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

	/* Emit actual guest observations so the host can compare both runs. */
	la $a0, prefix
	jal puts
	 nop
	li $t0, 0x80070000
	li $a0, RATE_HZ
	jal puthex
	 nop
	la $a0, rate_bytes_label
	jal puts
	 nop
	li $t0, 0x80070000
	lbu $a0, 0($t0)
	jal putbyte
	 nop
	li $t0, 0x80070000
	lbu $a0, 1($t0)
	jal putbyte
	 nop
	li $t0, 0x80070000
	lbu $a0, 2($t0)
	jal putbyte
	 nop
	la $a0, flags_label
	jal puts
	 nop
	li $t0, 0x800701d0
	lbu $a0, 0($t0)
	jal putbyte
	 nop
	li $t0, 0x800701d7
	lbu $a0, 0($t0)
	jal putbyte
	 nop
	la $a0, ring_label
	jal puts
	 nop
	li $t0, 0x800701f0
	lw $a0, 0($t0)
	jal puthex
	 nop
	la $a0, sum_label
	jal puts
	 nop
	move $a0, $s2
	jal puthex
	 nop
	la $a0, dma_label
	jal puts
	 nop
	li $t0, 0x80064044
	lw $a0, 0($t0)
	jal puthex
	 nop
	la $a0, control_label
	jal puts
	 nop
	li $t0, 0x80064040
	lw $a0, 0($t0)
	jal puthex
	 nop
	la $a0, size_label
	jal puts
	 nop
	li $t0, 0x80064054
	lw $a0, 0($t0)
	jal puthex
	 nop
	la $a0, newline
	jal puts
	 nop
halt:
	b halt
	 nop

fail_count:
	la $a0, fail_count_text
	b fail
	 nop
fail_ready:
	la $a0, fail_ready_text
	b fail
	 nop
fail_dma:
	la $a0, fail_dma_text
	b fail
	 nop
fail_payload:
	la $a0, fail_payload_text
fail:
	jal puts
	 nop
	b halt
	 nop
	.end _start

.ent puts
puts:
	or $t0, $a0, $zero
	lui $t1, 0xbf00
	ori $t1, $t1, 0x0900
puts_loop:
	lbu $t2, 0($t0)
	beqz $t2, puts_done
	 nop
	sb $t2, 0($t1)
	addiu $t0, $t0, 1
	b puts_loop
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

.ent putbyte
putbyte:
	andi $a0, $a0, 0xff
	srl $t0, $a0, 4
	andi $t0, $t0, 0x0f
	sltiu $t1, $t0, 10
	bnez $t1, putbyte_hi_digit
	 nop
	addiu $t0, $t0, 55
	b putbyte_hi_emit
	 nop
putbyte_hi_digit:
	addiu $t0, $t0, 48
putbyte_hi_emit:
	lui $t2, 0xbf00
	ori $t2, $t2, 0x0900
	sb $t0, 0($t2)
	andi $t0, $a0, 0x0f
	sltiu $t1, $t0, 10
	bnez $t1, putbyte_lo_digit
	 nop
	addiu $t0, $t0, 55
	b putbyte_lo_emit
	 nop
putbyte_lo_digit:
	addiu $t0, $t0, 48
putbyte_lo_emit:
	sb $t0, 0($t2)
	jr $ra
	 nop
.end putbyte

	.section .rodata
prefix: .asciz "RATE_STATE PASS ep0=NOT_INVOKED rate_hz=0x"
start_text: .asciz "RATE_STATE START\r\n"
producer_done_text: .asciz "RATE_STATE PRODUCER_DONE\r\n"
rate_bytes_label: .asciz " rate_bytes_le=0x"
flags_label: .asciz " flags_+1d0/+1d7=0x"
ring_label: .asciz " ring_count=0x"
sum_label: .asciz " payload_sum=0x"
dma_label: .asciz " dma_addr=0x"
control_label: .asciz " dma_control=0x"
size_label: .asciz " dma_size=0x"
newline: .asciz "\r\n"
fail_count_text: .asciz "RATE_STATE FAIL ring-count\r\n"
fail_ready_text: .asciz "RATE_STATE FAIL ready-latch\r\n"
fail_dma_text: .asciz "RATE_STATE FAIL dma-observation\r\n"
fail_payload_text: .asciz "RATE_STATE FAIL ring-payload\r\n"

.section .callback,"ax"
.set mips16
.ent usound_callback_stub
usound_callback_stub:
	jr $ra
	 nop
.end usound_callback_stub

	.section .driver,"ax"
	.incbin "usound_code.bin"
