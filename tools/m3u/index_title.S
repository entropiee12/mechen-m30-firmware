/* Extend the stock UTF-16 title's suffix search by one character for M3U8. */
.set mips16
.set noreorder
.text
.align 2
.globl index_title
.ent index_title
index_title:
    sltiu $2,12
    bteqz check_suffix
    b stock
check_suffix:
    lw $3,title_ptr
    lw $3,0($3)
    addu $4,$3,$2
    addiu $4,-10
    lhu $5,8($4)
    cmpi $5,56
    btnez stock
    lhu $5,0($4)
    cmpi $5,46
    btnez stock
    addiu $2,-2
stock:
    addiu $2,-8
    lw $3,resume
    jrc $3
.align 2
title_ptr: .word 0x9fc15534
resume: .word 0x40154d37
.end index_title
