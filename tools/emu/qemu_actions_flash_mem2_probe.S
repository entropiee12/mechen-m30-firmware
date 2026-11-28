/* Execute the public actions_flash ATJ2127 MIPS16 read_mem2 copy helper. */
	.set mips32
	.set noreorder
	.set noat

	.equ UART_BASE,  0xbf000900
	.equ HELPER_ARGS,0x80050020
	.equ HELPER_OUT, 0x80050028
	.equ SOURCE,     0x80060000
	.equ COPY_SIZE,  0x1d8

	.text
	.globl _start
	.ent _start
_start:
	/* The host tool writes {source, size} at helper+0x20. */
	lui	$t0, 0x8005
	ori	$t0, $t0, 0x0020
	la	$t1, source_bytes
	sw	$t1, 0($t0)
	li	$t1, COPY_SIZE
	sw	$t1, 4($t0)
	jalx	0x80050000
	 nop

	/* Confirm helper ABI: argument 0 becomes the output buffer pointer. */
	lui	$t0, 0x8005
	ori	$t0, $t0, 0x0020
	lw	$t1, 0($t0)
	lui	$t2, 0x8005
	ori	$t2, $t2, 0x0028
	bne	$t1, $t2, fail_abi
	 nop

	/* Compare the complete 0x1d8-byte maximum host chunk. */
	la	$t0, source_bytes
	lui	$t1, 0x8005
	ori	$t1, $t1, 0x0028
	li	$t2, COPY_SIZE
compare_loop:
	lbu	$t3, 0($t0)
	lbu	$t4, 0($t1)
	bne	$t3, $t4, fail_copy
	 nop
	addiu	$t0, $t0, 1
	addiu	$t1, $t1, 1
	addiu	$t2, $t2, -1
	bnez	$t2, compare_loop
	 nop

	la	$a0, pass_text
	jal	puts
	 nop
	b	halt
	 nop

fail_abi:
	la	$a0, fail_abi_text
	b	fail
	 nop
fail_copy:
	la	$a0, fail_copy_text
fail:
	jal	puts
	 nop
halt:
1:
	b	1b
	 nop
	.end _start

	.ent puts
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
	.end puts

	.section .rodata,"a"
pass_text:
	.asciz "ACTIONS FLASH MEM2 QEMU PASS copy=0x1d8 abi=source+size\r\n"
fail_abi_text:
	.asciz "ACTIONS FLASH MEM2 QEMU FAIL abi\r\n"
fail_copy_text:
	.asciz "ACTIONS FLASH MEM2 QEMU FAIL copy\r\n"

	.section .helper,"ax"
	.align 2
	.incbin "mem2_helper.bin"

	.section .source,"a"
	.align 2
source_bytes:
	.incbin "source_bytes.bin"
