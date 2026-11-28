# M30 LCD driver: static boundary

`drv_lcd.drv` is the low-level LCD controller helper. It accepts a window and
pixel data from higher layers; it does not choose the UI selection color or
the text background. The result is static Ghidra/raw-byte analysis only.

## Reproducible checks

```sh
PYTHONPATH=tools/emu python3 tools/emu/lcd_binary_probe.py \
  analysis/community/fw/drv_lcd.drv
bash tools/ghidra/run_lcd_probe.sh analysis/community/fw/drv_lcd.drv
```

The driver SHA-256 is identical in the local community, stock, first-pass,
V1.101.11, V1.101.12, and V5 extractions:

```text
d7dfa75c2296275508c89f2678591d994edc55aa13c2bc42853942ee1abd0b40
```

## Container and operations

The image has one resident MIPS16 payload:

| Field | Value |
|---|---:|
| file offset | `0x200` |
| length | `0x800` |
| destination | `0xbfc0a000` |
| BSS | `0x9fc14234`, `0xc8` bytes |
| entry field | `0x17009000` |
| static functions | 16 |

The useful static operations are:

- `FUN_bfc0a000` and `FUN_bfc0a02c` select command/data mode and transmit a
  byte buffer.
- `FUN_bfc0a070` submits a higher-level transfer with a 15-bit length.
- `FUN_bfc0a0a8` issues read setup (`0x1e`, `0x2e`) and converts returned
  18-bit-style RGB samples into RGB565 words.
- `FUN_bfc0a378` programs an LCD address window using commands `0x2a`
  (column), `0x2b` (row), and `0x2c` (memory write).
- `FUN_bfc0a438` writes command `0x36` and selects one of six controller
  orientation values (`0xc8`, `0xa8`, `0x48`, `0xe8`, `0xe8`, `0x88`).
- `FUN_bfc0a4e4` performs a controller transfer and waits for the hardware
  status bits to clear/set; the exact command source remains indirect.

The remaining functions configure the LCD peripheral and maintain a small
state/counter around reset/backlight/control callbacks. Their register names
are not available in the binary, so they are recorded as hardware operations,
not assigned vendor-specific meanings.

## Consequence for the red rectangle and clipping issue

The LCD driver receives explicit window coordinates and then sends the
controller's standard address-window commands. It has no visible color
literal or text measurement logic in the recovered functions. Therefore the
red/black rectangle and the clipped marquee are more likely produced by the
UI/resource layer (`drv_ui.drv`, `common.sty`, or the application paint path)
than by `drv_lcd.drv` itself.

The driver is still useful for emulation: a framebuffer harness can treat
`0x2a/0x2b/0x2c` as the authoritative window/write boundary and stop at the
same boundary instead of inventing a proprietary LCD protocol. No patch is
proposed from this analysis.

## Host emulator boundary

`tools/emu/lcd_transfer.py` now models this exact safe slice. It records the
raw orientation command (`0x36`) only for the six values recovered from the
driver, then records the column-window (`0x2a`), row-window (`0x2b`), and
memory-write (`0x2c`) command sequence before applying a bounded row-major
stream of RGB565 words to the host framebuffer. It intentionally does not
assign names or transform pixels for those orientation values. Partial writes
are supported because the driver exposes a transfer boundary rather than a
complete panel transaction. A stream that exceeds the active window, starts
without both windows, or contains a value outside one 16-bit word fails
closed.

The model intentionally does not assign meanings to the controller registers,
orientation values, status polling, IRQ timing, or the 15-bit higher-level
transfer length beyond the recovered command/window boundary.

## Real-code QEMU boundary

`bash tools/emu/run_qemu_lcd_probe.sh` executes the resident MIPS16 helpers
with only the command/data literal-pool words relocated to guest RAM. The
setup and transfer callbacks are explicit no-op shims; the window and
orientation functions remain byte-identical. Across stock, community,
first-pass, V1.101.11, V1.101.12, and V5, a synthetic window
`(2,3,4,5)` reaches the final `0x2c` command and row-end byte `7`, while
orientation `2` emits `0x36` followed by `0x48` and stores mode `2`. This is
stronger than a host-only command model, but it still does not identify the
physical MMIO register meanings, panel timing, or electrical output.
