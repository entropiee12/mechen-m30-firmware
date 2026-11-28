	/* Execute the real usound.drv MIPS16 module-call stubs under QEMU. */
	.set	mips32
	.set	noreorder
	.set	noat

	.section .vectors,"ax"
	.globl	general_exception
	.ent	general_exception
general_exception:
	/* The module call uses break 0. Return through the interrupted RA,
	 * which is the caller continuation established by JALX/JALRC. */
	lui	$k0, 0x8006
	sw	$a0, 0($k0)
	sw	$a1, 4($k0)
	sw	$a2, 8($k0)
	sw	$a3, 0xc($k0)
	sw	$v1, 0x10($k0)
	mfc0	$k1, $13
	sw	$k1, 0x18($k0)
	mfc0	$k1, $14
	sw	$k1, 0x1c($k0)
	mfc0	$k1, $8
	sw	$k1, 0x20($k0)
	mfc0	$k1, $12
	sw	$k1, 0x24($k0)
	li	$k1, 1
	sw	$k1, 0x14($k0)
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
	/* Use a RAM-backed general exception vector instead of Malta's PROM. */
	lui	$k0, 0x8001
	mtc0	$k0, $15, 1
	nop
	mfc0	$k1, $12
	li	$t1, 0xffbfffff
	and	$k1, $k1, $t1
	mtc0	$k1, $12
	nop
	lui	$t0, 0x8006
	sw	$zero, 0($t0)
	sw	$zero, 4($t0)
	sw	$zero, 8($t0)
	sw	$zero, 0xc($t0)
	sw	$zero, 0x10($t0)
	sw	$zero, 0x14($t0)
	sw	$zero, 0x18($t0)
	sw	$zero, 0x1c($t0)
	sw	$zero, 0x20($t0)
	sw	$zero, 0x24($t0)

	/* Match the callback ABI used by FUN_bfc31654: buffer, destination,
	 * byte count, and the fourth zero argument. */
	lui	$a0, 0x8003
	ori	$a0, $a0, 0x1111
	lui	$a1, 0x8003
	ori	$a1, $a1, 0x2222
	li	$a2, 0x40
	move	$a3, $zero
	/* The low bit is ISA metadata; JALX takes the aligned entry address. */
	jalx	0x800526a8
	 nop

	lw	$t1, 0x14($t0)
	li	$t2, 1
	bne	$t1, $t2, fail
	 nop
	lw	$t1, 0x10($t0)
	li	$t2, 0x10
	bne	$t1, $t2, fail
	 nop
	lw	$t1, 0($t0)
	li	$t2, 0x80031111
	bne	$t1, $t2, fail
	 nop
	lw	$t1, 4($t0)
	li	$t2, 0x80032222
	bne	$t1, $t2, fail
	 nop
	lw	$t1, 8($t0)
	li	$t2, 0x40
	bne	$t1, $t2, fail
	 nop
	lw	$t1, 0xc($t0)
	bnez	$t1, fail
	 nop
	lw	$t1, 0x18($t0)
	srl	$t1, $t1, 2
	andi	$t1, $t1, 0x1f
	li	$t2, 9
	bne	$t1, $t2, fail
	 nop

	jal	test_module0f
	 nop
	bnez	$v0, fail
	 nop

	jal	print_exception_trace
	 nop
	b	halt
	 nop

fail:
	la	$a0, fail_text
	jal	puts
	 nop

halt:
	1:
	b	1b
	 nop
	.end	_start

	.section .text.hex,"ax"
	.ent	test_module0f
test_module0f:
	/* Exercise the adjacent module-0x0f stub with the setup path's observed
	 * argument shape; this checks the trap envelope, not the fixed handler. */
	addiu	$sp, $sp, -16
	sw	$ra, 12($sp)
	lui	$t0, 0x8006
	sw	$zero, 0x14($t0)
	li	$a0, 0x14
	lui	$a1, 0x8005
	ori	$a1, $a1, 0x24ec
	move	$a2, $zero
	li	$a3, 0x14
	jalx	0x800526ac
	 nop

	lui	$t0, 0x8006
	lw	$t1, 0x14($t0)
	li	$t2, 1
	bne	$t1, $t2, module0f_fail
	 nop
	lw	$t1, 0x10($t0)
	li	$t2, 0x0f
	bne	$t1, $t2, module0f_fail
	 nop
	lw	$t1, 0($t0)
	li	$t2, 0x14
	bne	$t1, $t2, module0f_fail
	 nop
	lw	$t1, 4($t0)
	li	$t2, 0x800524ec
	bne	$t1, $t2, module0f_fail
	 nop
	lw	$t1, 8($t0)
	bnez	$t1, module0f_fail
	 nop
	lw	$t1, 0xc($t0)
	li	$t2, 0x14
	bne	$t1, $t2, module0f_fail
	 nop
	lw	$t1, 0x18($t0)
	srl	$t1, $t1, 2
	andi	$t1, $t1, 0x1f
	li	$t2, 9
	bne	$t1, $t2, module0f_fail
	 nop
	move	$v0, $zero
	lw	$ra, 12($sp)
	addiu	$sp, $sp, 16
	jr	$ra
	 nop
module0f_fail:
	li	$v0, 1
	lw	$ra, 12($sp)
	addiu	$sp, $sp, 16
	jr	$ra
	 nop
	.end	test_module0f

	.ent	print_exception_trace
print_exception_trace:
	lui	$s0, 0x8006
	la	$a0, pass_text
	jal	puts
	 nop
	lw	$a0, 0x18($s0)
	srl	$a0, $a0, 2
	andi	$a0, $a0, 0x1f
	jal	put_hex
	 nop
	la	$a0, epc_text
	jal	puts
	 nop
	lw	$a0, 0x1c($s0)
	jal	put_hex
	 nop
	la	$a0, badvaddr_text
	jal	puts
	 nop
	lw	$a0, 0x20($s0)
	jal	put_hex
	 nop
	la	$a0, status_text
	jal	puts
	 nop
	lw	$a0, 0x24($s0)
	jal	put_hex
	 nop
	la	$a0, line_end
	jal	puts
	 nop
	jr	$ra
	 nop
	.end	print_exception_trace

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

	.ent	put_hex
put_hex:
	move	$t0, $a0
	li	$t1, 8
hex_loop:
	srl	$t2, $t0, 28
	andi	$t2, $t2, 0xf
	sltiu	$t3, $t2, 10
	bnez	$t3, hex_digit
	 nop
	addiu	$t2, $t2, 87
	b	hex_emit
	 nop
hex_digit:
	addiu	$t2, $t2, 48
hex_emit:
	lui	$t3, 0xbf00
	ori	$t3, $t3, 0x0900
	sb	$t2, 0($t3)
	sll	$t0, $t0, 4
	addiu	$t1, $t1, -1
	bnez	$t1, hex_loop
	 nop
	jr	$ra
	 nop
	.end	put_hex

	.section .rodata
pass_text:
	.asciz	"REAL USOUND MODULE-CALL PASS modules=0x10,0x0f args=4 exc=0x"
epc_text:
	.asciz	" epc=0x"
badvaddr_text:
	.asciz	" badvaddr=0x"
status_text:
	.asciz	" status=0x"
line_end:
	.asciz	"\r\n"
fail_text:
	.asciz	"REAL USOUND CALLBACK FAIL\r\n"

	.section .driver,"ax"
	.align	2
	.incbin	"usound_code.bin"
