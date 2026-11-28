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
    mtc0    $zero, $5         # PageMask = 4 KiB
    lui     $t0, 0x1
    ori     $t0, $t0, 0x2000 # pair containing the vector save area 0x13dcc
    mtc0    $t0, $10          # EntryHi = 0x12000, ASID 0
    addiu   $t0, $zero, 0x49f # PFN 0x12, valid, dirty, global, cached
    mtc0    $t0, $2           # EntryLo0: physical page 0x12
    addiu   $t0, $zero, 0x4df
    mtc0    $t0, $3           # EntryLo1: physical page 0x13
    mtc0    $zero, $0         # Index = 0
    tlbwi
    ehb
    lui     $t0, 0xbfc0
    ori     $t0, $t0, 0x8000
    mtc0    $t0, $15, 1       # EBase = 0xbfc08000
    ehb
    mtc0    $zero, $12        # clear BEV and ERL; use EBase + 0x180
    ehb

    # Canary-fill the complete five-word frame and its adjacent words. The
    # vector should write the first three words; the real xsys entry should
    # append EPC and RA in words four and five.
    lui     $t0, 0x8001
    ori     $t0, $t0, 0x3dc8
    lui     $t1, 0xcccc
    ori     $t1, $t1, 0xcccc
    sw      $t1, 0($t0)        # leading guard
    lui     $t0, 0x8001
    ori     $t0, $t0, 0x3dcc
    addiu   $t2, $zero, 5
probe_seed_frame:
    sw      $t1, 0($t0)
    addiu   $t0, $t0, 4
    addiu   $t2, $t2, -1
    bnez    $t2, probe_seed_frame
    nop
    lui     $t0, 0x8001
    ori     $t0, $t0, 0x3de0
    sw      $t1, 0($t0)        # trailing guard

    lui     $ra, 0x1234
    ori     $ra, $ra, 0x5678
.globl probe_break
probe_break:
    break   0                 # ExcCode 9 must dispatch through table slot 9
probe_fail:
    j       probe_fail
    nop

.globl probe_pass
probe_pass:
    lui     $t0, 0x8001
    ori     $t0, $t0, 0x3dcc # M30 vector's EPC save location
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
    sb      $t1, 0($t0)       # print P
    addiu   $t1, $zero, 0x41
    sb      $t1, 0($t0)       # print A
    addiu   $t1, $zero, 0x53
    sb      $t1, 0($t0)       # print S
    sb      $t1, 0($t0)       # print S
    j       probe_halt
    nop

.globl probe_xsys_pass
probe_xsys_pass:
    # Reaching this checkpoint means the vector dispatched through the
    # instrumented slot-9 bridge and the real xsys entry returned here.
    lui     $t0, 0x8001
    ori     $t0, $t0, 0x3dd8
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
    lui     $t0, 0x8001
    ori     $t0, $t0, 0x3dc8
    lui     $t2, 0xcccc
    ori     $t2, $t2, 0xcccc
    lw      $t1, 0($t0)
    bne     $t1, $t2, probe_fail
    nop
    lui     $t0, 0x8001
    ori     $t0, $t0, 0x3de0
    lw      $t1, 0($t0)
    bne     $t1, $t2, probe_fail
    nop
    j       probe_pass
    nop

probe_halt:
    j       probe_halt
    nop

.section .handler, "ax", @progbits
.globl handler_stub
handler_stub:
    j       probe_pass
    nop

.section .checkpoint, "ax", @progbits
.globl xsys_checkpoint_stub
xsys_checkpoint_stub:
    j       probe_xsys_pass
    nop
