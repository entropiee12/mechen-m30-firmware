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

	/* SET_INTERFACE alt=0: the handler ACKs but preserves seeded ring state. */
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
	li $t2, 0x41424344
	bne $t1, $t2, fail_alt0_state
	 nop
	li $t0, 0x800601ec
	lw $t1, 0($t0)
	li $t2, 0x51525354
	bne $t1, $t2, fail_alt0_state
	 nop
	li $t0, 0x800601f0
	lw $t1, 0($t0)
	li $t2, 0x61626364
	bne $t1, $t2, fail_alt0_state
	 nop
	li $t0, 0x80060213
	lbu $t1, 0($t0)
	li $t2, 0x71
	bne $t1, $t2, fail_alt0_state
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

.section .rodata
pass_text: .asciz "REAL USOUND EP0 PASS alt1=resets alt0=preserves setup-copy=1 ack=1\r\n"
fail_alt1_ack_text: .asciz "REAL USOUND EP0 FAIL alt1-ack\r\n"
fail_setup_text: .asciz "REAL USOUND EP0 FAIL setup-copy\r\n"
fail_alt1_state_text: .asciz "REAL USOUND EP0 FAIL alt1-state\r\n"
fail_alt0_ack_text: .asciz "REAL USOUND EP0 FAIL alt0-ack\r\n"
fail_alt0_state_text: .asciz "REAL USOUND EP0 FAIL alt0-state\r\n"

.section .driver,"ax"
.incbin "usound_code.bin"
