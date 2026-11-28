/* MIPS16 host stubs for the isolated FUN_40d54f2c smoke probe. */
	.set	mips16
	.set	noreorder
	.ifndef EVENT_CODE
	.equ	EVENT_CODE, 0x2906
	.endif
	.ifndef SEQUENCE_MODE
	.equ	SEQUENCE_MODE, 0
	.endif
	.section .stubs,"ax"

	.org	0x00
	.globl	ui_dispatch_stub
ui_dispatch_stub:
	li	$2, 1
	jr	$31
	nop

	.org	0x20
	.globl	ui_read_status_stub
ui_read_status_stub:
	li	$2, 0
	sb	$2, 0($4)
	sb	$2, 0($5)
	li	$2, 1
	jr	$31
	nop

	.org	0x40
	.globl	ui_set_backlight_stub
ui_set_backlight_stub:
	li	$2, 1
	jr	$31
	nop

	.org	0x60
	.globl	timer_stub
timer_stub:
	li	$2, 0
	jr	$31
	nop

	.org	0x80
	.globl	key_read_begin_stub
key_read_begin_stub:
	li	$2, 1
	jr	$31
	nop

	.org	0xa0
	.globl	key_read_event_stub
key_read_event_stub:
	.if SEQUENCE_MODE
	/* Sequence mode selects next,next,previous,redraw. */
	/* Keep the counter outside the event handler's reinitialized scratch. */
	/* Keep probe state above the handler's stack frame. */
	addiu	$2, $29, 0x400
	lbu	$3, 0($2)
	addiu	$3, $3, 1
	sb	$3, 0($2)
	addiu	$3, $3, -1
	sll	$3, $3, 1
	addiu	$3, $3, 4
	/* Event table is kept next to the isolated counter, outside scratch. */
	addiu	$2, $29, 0x400
	addu	$3, $3, $2
	lhu	$2, 0($3)
	sh	$2, 0($5)
	.else
	li	$2, EVENT_CODE
	sh	$2, 0($5)
	.endif
	li	$2, 1
	jr	$31
	nop

	.org	0xc0
	.globl	key_fallback_stub
key_fallback_stub:
	li	$2, 0
	jr	$31
	nop

	.org	0xe0
	.globl	key_poll_stub
key_poll_stub:
	li	$2, 0
	jr	$31
	nop
