# Public ATJ2127 UI ROM reference

This is a reference-only result. `UI0_ROM0.BIN` is the public ATJ2127 SDK UI
ROM, not a dump from the M30. Its addresses must not be treated as M30
addresses, and this work does not justify a firmware patch.

The input comes from the public [PD196_ATJ2127 repository](https://github.com/Suber/PD196_ATJ2127),
commit `b964feb76a7890a0ee32db188689da3254b724f9`. The SDK linker file defines
the UI ROM region as `SROM_UI_ADDR = 0x0b400` and `SROM_UI_SIZE = 0x0c00`.
The binary therefore maps at the MIPS virtual reference base `0xbfc0b400`.

## Reproduction

With Ghidra installed and the public SDK checkout available at its usual
temporary path, run:

```sh
bash tools/ghidra/run_reference_uirom_probe.sh
```

An alternate path can be supplied as the first argument. The script requires
an exact 0xc00-byte input, uses an isolated Ghidra user directory, maps the
binary read-only, and checks the decompiler output before printing its PASS
marker.

## Mapped entry shapes

| Reference address | Likely public API role | Evidence from the mapped/decompiled code |
| --- | --- | --- |
| `0xbfc0b5ac` | `romf_show_pic` | Loads an image resource and dispatches through the image drawing path. |
| `0xbfc0b618` | `romf_put_sd_image` | Validates a rectangle, computes a pixel-byte count from width and height, reads SD data, and sends it to the display/DMA path. |
| `0xbfc0b6cc` | `romf_fill_rect` | Validates bounds, fills RGB565 pixels with a supplied color, and transfers the result to the display. |
| `0xbfc0b78c` | `romf_black_char_to_color` | Implements the public character recoloring helper used by the ROM string path. |
| `0xbfc0b85c` | `romf_char_fill` | Draws/fills a character-sized region for the ROM text renderer. |
| `0xbfc0b964` | `romf_read_str_bgpic` | Selects background behavior by mode, including character-fill color, then invokes the background-image readback hook. |
| `0xbfc0bc04` | `romf_put_string_line` | Accepts a string descriptor and region/mode parameters, scans character widths, renders the line, and handles the final transfer. |
| `0xbfc0bef4` | `romf_get_string_length` | Scans the string descriptor and writes the calculated display length while respecting the supplied limit. |

These are semantic matches against the public SDK sources and the observed
decompiler shapes. They are useful as signatures to search for in a future
real M30 ROM dump, not as proof that the M30 uses the same addresses or exact
implementation.

## Consequence for the M30 work

The next ROM-specific step is still to obtain a real 0x40000-byte M30 boot ROM
dump through the device's physical ADFU/USB path. Once available, the existing
boot-ROM probe can map it and these public UI call shapes can be compared
against resident code. Until then, the defensible work is static comparison,
emulator modeling, and read-only probes; no HEX should be produced from these
reference addresses.
