.set noreorder
.set noat
.set mips32r2

.section .reset, "ax", @progbits
.globl _start
_start:
    j       probe_main
    nop

.section .text, "ax", @progbits
.globl probe_main
probe_main:
    # First verify QEMU's direct MIPS32 -> MIPS16 -> MIPS32 transition.
    jalx    probe_mips16_target
    nop
    addiu   $t0, $zero, 0x1234
    bne     $v0, $t0, probe_fail
    nop

    # The real vector stores state in kuseg. Map the 0x12000/0x13000 pair.
    lui     $t0, 0x0001
    ori     $t0, $t0, 0x2000
    mtc0    $t0, $10          # EntryHi = 0x12000, ASID 0
    addiu   $t0, $zero, 0x49f # PFN 0x12, valid, dirty, global, cached
    mtc0    $t0, $2           # EntryLo0: physical page 0x12
    addiu   $t0, $zero, 0x4df
    mtc0    $t0, $3           # EntryLo1: physical page 0x13
    mtc0    $zero, $5         # PageMask = 4 KiB
    mtc0    $zero, $0         # Index = 0
    tlbwi
    ehb

    lui     $sp, 0x8003
    ori     $sp, $sp, 0xfe00
    lui     $t0, 0xbfc0
    ori     $t0, $t0, 0x8000
    mtc0    $t0, $15, 1       # EBase = 0xbfc08000
    ehb
    mtc0    $zero, $12        # clear BEV and ERL
    ehb

    # Sentinel-fill the RAM table. The BIOS vector and setter each have one
    # audited table-base literal redirected to this location by the image builder.
    lui     $t0, 0x8001
    ori     $t0, $t0, 0xa9a4
    lui     $t1, 0xcccc
    ori     $t1, $t1, 0xcccc
    addiu   $t2, $zero, 32
probe_fill_table:
    sw      $t1, 0($t0)
    addiu   $t0, $t0, 4
    addiu   $t2, $t2, -1
    bnez    $t2, probe_fill_table
    nop

    # Enter the extracted constructor at its original firmware address.
    jal     0xbfc32bc8
    nop
    j       probe_fail
    nop

.section .boundary, "ax", @progbits
.globl probe_constructor_boundary
probe_constructor_boundary:
    # Verify every accepted slot against the extracted constructor's output.
    lui     $t0, 0x8001
    ori     $t0, $t0, 0xa9a4
    move    $t1, $zero
probe_check_slot:
    lui     $t2, 0xbfc2
    addiu   $t2, $t2, -25995 # default handler 0xbfc19a75
    beq     $t1, $zero, probe_expect_slot0
    nop
    addiu   $t3, $zero, 9
    beq     $t1, $t3, probe_expect_slot9
    nop
    addiu   $t3, $zero, 10
    beq     $t1, $t3, probe_expect_slot10
    nop
    j       probe_compare_slot
    nop
probe_expect_slot0:
    lui     $t2, 0xbfc2
    addiu   $t2, $t2, -27100 # override 0 = 0xbfc19624
    j       probe_compare_slot
    nop
probe_expect_slot9:
    lui     $t2, 0xbfc0
    ori     $t2, $t2, 0x6f18 # fixed-ROM handler pointer
    j       probe_compare_slot
    nop
probe_expect_slot10:
    lui     $t2, 0xbfc2
    addiu   $t2, $t2, -27180 # override 10 = 0xbfc195d4
probe_compare_slot:
    lw      $t4, 0($t0)
    bne     $t2, $t4, probe_fail
    nop
    addiu   $t0, $t0, 4
    addiu   $t1, $t1, 1
    slti    $t3, $t1, 14
    bnez    $t3, probe_check_slot
    nop

    # Constructor must leave all slots outside 0..13 untouched.
    lui     $t1, 0xcccc
    ori     $t1, $t1, 0xcccc
    lw      $t4, 0($t0)       # slot 14
    bne     $t1, $t4, probe_fail
    nop
    lw      $t4, 68($t0)      # slot 31
    bne     $t1, $t4, probe_fail
    nop

    lui     $ra, 0x1234
    ori     $ra, $ra, 0x5678
.globl probe_break
probe_break:
    break   0                 # real vector must dispatch through constructed slot 9
probe_fail:
    j       probe_fail
    nop

.globl probe_pass
probe_pass:
    lui     $t0, 0x8001
    ori     $t0, $t0, 0x3dcc
    lw      $t1, 0($t0)
    lui     $t2, %hi(probe_break)
    addiu   $t2, $t2, %lo(probe_break)
    bne     $t1, $t2, probe_fail
    nop
    lw      $t1, 4($t0)
    lui     $t2, 0x1234
    ori     $t2, $t2, 0x5678
    bne     $t1, $t2, probe_fail
    nop
    lw      $t1, 8($t0)
    andi    $t1, $t1, 0x007c
    addiu   $t2, $zero, 0x24
    bne     $t1, $t2, probe_fail
    nop

    lui     $t0, 0xbf00
    ori     $t0, $t0, 0x0900  # Malta FPGA CBUS UART (serial_hd(2))
    addiu   $t1, $zero, 0x50
    sb      $t1, 0($t0)
    addiu   $t1, $zero, 0x41
    sb      $t1, 0($t0)
    addiu   $t1, $zero, 0x53
    sb      $t1, 0($t0)
    sb      $t1, 0($t0)
    j       probe_halt
    nop

probe_halt:
    j       probe_halt
    nop

.section .handler, "ax", @progbits
.globl handler_stub
handler_stub:
    j       probe_pass
    nop

.section .mips16, "ax", @progbits
.set mips16
.globl probe_mips16_target
probe_mips16_target:
    li      $v0, 0x1234
    jr      $ra
    nop
