/* Execute the real drv_lcd.drv window/orientation helpers in QEMU. */
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

	/* Guest-RAM stand-ins for command/data control and data registers. */
	sw	$zero, 8($t0)
	sw	$zero, 0xc($t0)
	sw	$zero, 0x10($t0)
	sw	$zero, 0x14($t0)
	sw	$zero, 0x18($t0)
	li	$t1, 3
	sw	$t1, 0x1c($t0)

	/* Window: x=2..5, y=3..7, orientation mode 3. */	lui	$t1, 0x8006
	addiu	$t1, $t1, 0x1000
	li	$t2, 2
	sh	$t2, 0($t1)
	li	$t2, 3
	sh	$t2, 2($t1)
	li	$t2, 4
	sh	$t2, 4($t1)
	li	$t2, 5
	sh	$t2, 6($t1)
	or	$a0, $t1, $zero
	jalx	0x80050378
	 nop
	la	$a0, window_text
	jal	check_window
	 nop

	/* Orientation 2 must emit MADCTL 0x48 and preserve mode=2. */	li	$a0, 2
	jalx	0x80050438
	 nop
	la	$a0, orientation_text
	jal	check_orientation
	 nop

	la	$a0, pass_text
	jal	puts
	 nop
	b	halt
	 nop

check_window:
	/* a0 points to the diagnostic label; preserve it while checking. */
	or	$t9, $a0, $zero
	lui	$t0, 0x8006
	lw	$t1, 0xc($t0)
	li	$t2, 0x2c
	bne	$t1, $t2, fail_window
	 nop
	lw	$t1, 0x14($t0)
	li	$t2, 7
	bne	$t1, $t2, fail_window
	 nop
	lw	$t1, 0x10($t0)
	li	$t2, 1
	bne	$t1, $t2, fail_window
	 nop
	jr	$ra
	 nop

check_orientation:
	or	$t9, $a0, $zero
	lui	$t0, 0x8006
	lw	$t1, 0xc($t0)
	li	$t2, 0x36
	bne	$t1, $t2, fail_orientation
	 nop
	lw	$t1, 0x14($t0)
	li	$t2, 0x48
	bne	$t1, $t2, fail_orientation
	 nop
	lw	$t1, 0x18($t0)
	li	$t2, 2
	bne	$t1, $t2, fail_orientation
	 nop
	lw	$t1, 0x1c($t0)
	li	$t2, 2
	bne	$t1, $t2, fail_orientation
	 nop
	jr	$ra
	 nop

fail_window:
	la	$a0, window_fail_text
	b	fail
	 nop
fail_orientation:
	la	$a0, orientation_fail_text
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
	.globl	lcd_callback_stub
	.ent	lcd_callback_stub
lcd_callback_stub:
	jr	$ra
	 nop
	.end	lcd_callback_stub
	.set	mips32

	.section .rodata
pass_text:
	.asciz	"REAL LCD QEMU PASS window=2,3,4,5 orientation=2\r\n"
window_text:
	.asciz	"window\r\n"
orientation_text:
	.asciz	"orientation\r\n"
window_fail_text:
	.asciz	"REAL LCD QEMU FAIL window\r\n"
orientation_fail_text:
	.asciz	"REAL LCD QEMU FAIL orientation\r\n"

	.section .driver,"ax"
	.align	2
	.incbin	"lcd_code.bin"
