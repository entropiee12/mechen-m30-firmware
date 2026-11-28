/* Execute the real drv_dac.drv byte-transfer helpers with RAM GPIO context. */
	.set	mips32
	.set	noreorder
	.set	noat

	.equ	UART_BASE, 0xbf000900
	.equ	CTX,       0x80060000
	.equ	REG_A,     0x80060100
	.equ	REG_B,     0x80060104
	.equ	REG_C,     0x80060108
	.equ	REG_D,     0x8006010c
	.equ	REG_IO,    0x80060110
	.equ	DELAY,     0x80060200
	.equ	STACK_TOP, 0x8006fff0

	.text
	.globl	_start
	.ent	_start
_start:
	/* The fifth argument is the delay-loop byte at 16(sp). */
	lui	$sp, 0x8006
	ori	$sp, $sp, 0xfff0
	sb	$zero, 16($sp)

	/* Build the recovered context: pointers at +4/+8/+10/+14/+18 and masks. */
	lui	$t0, 0x8006
	ori	$t0, $t0, 0x0000
	lui	$t1, 0x8006
	ori	$t1, $t1, 0x0100
	sw	$t1, 4($t0)
	lui	$t1, 0x8006
	ori	$t1, $t1, 0x0104
	sw	$t1, 8($t0)
	li	$t1, 1
	sw	$t1, 12($t0)
	lui	$t1, 0x8006
	ori	$t1, $t1, 0x0108
	sw	$t1, 16($t0)
	lui	$t1, 0x8006
	ori	$t1, $t1, 0x010c
	sw	$t1, 20($t0)
	lui	$t1, 0x8006
	ori	$t1, $t1, 0x0110
	sw	$t1, 24($t0)
	li	$t1, 0x20
	sw	$t1, 28($t0)

	/* A direct byte shift proves the real eight-bit transmit path executes. */	li	$a0, 0xa5
	la	$a1, CTX
	la	$a2, DELAY
	jalx	0x800504d4
	 nop
	/* The last transmitted bit of 0xa5 is one: IO retains the mask. */	lui	$t0, 0x8006
	ori	$t0, $t0, 0x0110
	lw	$t1, 0($t0)
	li	$t2, 0x20
	bne	$t1, $t2, fail_tx_bit
	 nop

	/* Send command 0x12 and two bytes through the real public helper. */	lui	$t0, 0x8006
	ori	$t0, $t0, 0x0204
	li	$t1, 0xa5
	sb	$t1, 0($t0)
	li	$t1, 0x5a
	sb	$t1, 1($t0)
	/* The public helper polls the same GPIO word that setup drives high.
	 * A zero mask is the fail-closed synthetic ready context: it models no
	 * electrical level and lets us test byte control flow without guessing it. */
	lui	$t0, 0x8006
	ori	$t0, $t0, 0x0000
	sw	$zero, 28($t0)
	la	$a0, 0x80060204
	li	$a1, 0x12
	li	$a2, 2
	la	$a3, CTX
	jalx	0x80050360
	 nop
	/* v0=1 means ready and all two bytes were accepted. */	li	$t2, 1
	bne	$v0, $t2, fail_tx_result
	 nop
	li	$t1, 0x20
	sw	$t1, 28($t0)

	/* Direct receive with IO high returns eight sampled one bits. */	lui	$t0, 0x8006
	ori	$t0, $t0, 0x0110
	li	$t1, 0x20
	sw	$t1, 0($t0)
	la	$a0, CTX
	la	$a1, DELAY
	jalx	0x80050540
	 nop
	li	$t2, 0xff
	bne	$v0, $t2, fail_rx_high
	 nop

	/* IO low gives the complementary all-zero sample. */	sw	$zero, 0($t0)
	la	$a0, CTX
	la	$a1, DELAY
	jalx	0x80050540
	 nop
	bnez	$v0, fail_rx_low
	 nop

	la	$a0, pass_text
	jal	puts
	 nop
	b	halt
	 nop

fail_tx_bit:
	la	$a0, fail_tx_bit_text
	b	fail
	 nop
fail_tx_result:
	la	$a0, fail_tx_result_text
	b	fail
	 nop
fail_rx_high:
	la	$a0, fail_rx_high_text
	b	fail
	 nop
fail_rx_low:
	la	$a0, fail_rx_low_text

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
	.asciz	"REAL DAC QEMU PASS tx=8bit/2bytes rx=0xff/0x00\r\n"
fail_tx_bit_text:
	.asciz	"REAL DAC QEMU FAIL tx-last-bit\r\n"
fail_tx_result_text:
	.asciz	"REAL DAC QEMU FAIL tx-result\r\n"
fail_rx_high_text:
	.asciz	"REAL DAC QEMU FAIL rx-high\r\n"
fail_rx_low_text:
	.asciz	"REAL DAC QEMU FAIL rx-low\r\n"

	.section .driver,"ax"
	.align	2
	.incbin	"dac_code.bin"

	/* MIPS16 helper calls retain the original bank's 256 MiB jump region. */
	.section .driver_alias,"ax"
	.align	2
	.incbin	"dac_code.bin"
