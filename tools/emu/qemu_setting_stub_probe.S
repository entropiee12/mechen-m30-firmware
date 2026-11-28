/* Execute setting.ap's import-15 and import-6 stubs under QEMU. */
	.set mips32
	.set noreorder
	.set noat
	.section .vectors,"ax"
	.globl general_exception
general_exception:
	lui $k0,0x8006
	sw $a0,0($k0)
	sw $a1,4($k0)
	sw $a2,8($k0)
	sw $a3,0xc($k0)
	sw $v1,0x10($k0)
	mfc0 $k1,$13
	sw $k1,0x18($k0)
	mfc0 $k1,$14
	sw $k1,0x1c($k0)
	mtc0 $ra,$14
	nop
	eret
	nop

	.text
	.globl _start
_start:
	lui $sp,0x8004
	ori $sp,$sp,0xfff0
	lui $k0,0x8001
	mtc0 $k0,$15,1
	nop
	mfc0 $k1,$12
	li $t1,0xffbfffff
	and $k1,$k1,$t1
	mtc0 $k1,$12
	nop
	lui $t0,0x8006
	sw $zero,0x10($t0)
	li $a0,0
	li $a1,0
	li $a2,0
	li $a3,0x1c
	.word 0x740149ab /* JALX to 0x800526ac: known-working MIPS16 site */
	nop
	lw $t1,0x10($t0)
	li $t2,0x0f
	bne $t1,$t2,fail
	nop
	lw $t1,0($t0)
	bnez $t1,fail
	nop
	lw $t1,4($t0)
	bnez $t1,fail
	nop
	lw $t1,8($t0)
	bnez $t1,fail
	nop
	lw $t1,0xc($t0)
	li $t2,0x1c
	bne $t1,$t2,fail
	nop
	sw $zero,0x10($t0)
	li $a0,1
	li $a3,0x1e
	.word 0x740149ac /* JALX to appended import-6 MIPS16 stub at 0x800526b0 */
	nop
	lw $t1,0x10($t0)
	li $t2,0x06
	bne $t1,$t2,fail
	nop
	lw $t1,0($t0)
	li $t2,1
	bne $t1,$t2,fail
	nop
	lw $t1,4($t0)
	bnez $t1,fail
	nop
	lw $t1,8($t0)
	bnez $t1,fail
	nop
	lw $t1,0xc($t0)
	li $t2,0x1e
	bne $t1,$t2,fail
	nop
	la $a0,pass_msg
	b print_msg
	nop
fail:
	la $a0,fail_msg
print_msg:
	move $t3,$a0
	lui $t4,0xbf00
	ori $t4,$t4,0x0900
1:
	lbu $t5,0($t3)
	beqz $t5,2f
	nop
	sb $t5,0($t4)
	addiu $t3,$t3,1
	b 1b
	nop
2:
	b 2b
	nop

	.section .driver,"ax"
	.incbin "usound_code.bin"

	.section .rodata
pass_msg: .asciz "SETTING IMPORT15/IMPORT6 QEMU ABI PASS\n"
fail_msg: .asciz "SETTING IMPORT15/IMPORT6 QEMU ABI FAIL\n"
