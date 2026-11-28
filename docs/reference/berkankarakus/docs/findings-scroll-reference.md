# Folder marquee: reference-source match and 64-byte experiment

This note combines the public US212A/ATJ2127 UI-driver source with the M30
V1.101.10 / community V1.292.00 static binaries. It is not a device or full
ATJ2127-QEMU result.

The public reference snapshot used for comparison is
[Suber/PD196_ATJ2127](https://github.com/Suber/PD196_ATJ2127), commit
`b964feb76a7890a0ee32db188689da3254b724f9`. It is used only as a source/API
reference; its generated resources and coordinates are not copied into M30.

The source-to-bank relationship is now checked by
`tools/emu/reference_ui_contract.py`. With the local reference tree extracted
at `/private/tmp/PD196_ATJ2127`, run:

```sh
PYTHONPATH=tools/emu python3 tools/emu/reference_ui_contract.py
```

The probe verifies the linker-level mapping `B05 ->
bank_b_ui_scroll_string.o` and `B16 -> bank_b_ui_string_sdimage.o`, then
checks that `clear_remain_pixel_sdimage()` reads the saved background, sets a
scroll LCD region, and submits it through DMA. This is a public-SDK source
contract cross-check; it does not claim that those source objects are present
as payload banks in the M30 `drv_ui.drv`.

The accompanying public US212A design manual also separates the relevant UI
operations: string scrolling, string measurement, line splitting, picture
display, and reading a picture region back into a buffer are distinct driver
interfaces. Its graphics section likewise distinguishes solid-color fills from
image-backed fills. This supports the existing interpretation of the M30 B3
indirect calls: a `put_string_line_sdimage`-shaped call can be the text draw
side of the restore path, but it is not by itself proof that the background
provider is a solid fill. The manual is a generic API reference, not a M30
binary mapping: [US212A UI-driver design manual](https://tchumim.com/assets/uploads/files/1622479367338-%D7%9E%D7%93%D7%A8%D7%99%D7%9A-%D7%9C%D7%AA%D7%9B%D7%A0%D7%95%D7%9F-%D7%90%D7%A4%D7%9C%D7%99%D7%A7%D7%A6%D7%99%D7%95%D7%AA-us212a.pdf).

## What the reference source explains

The active directory item in the M30 `common.sty` listbox is not a plain
colour fill:

- `data_x=35`, `data_width=205`, `data_height=16`, row height `30`;
- active background mode is `SPIC` (`item.attrib` bits 10-11 = `1`);
- active long-string mode is `SCROLL` (`str_attrib` bits 4-5 = `1`);
- active background resource is the 310x29 selection bitmap (resource 80 in
  the extracted `common.sty`), currently red in the safe UI candidate.

The `common.sty` resource is 80, a 310x29 bitmap whose pixels are all RGB565
`0xd000` red. The reference `show_list_item()` first draws that selected
bitmap. In that reference implementation, scrolling uses `load_bgpic_sd()`,
`CHAR_FILL_SDIMAGE`, and `clear_remain_pixel_sdimage()` to restore the moving
text region. This is generic SDK behavior, not yet mapped to the M30 browser
call chain. The red selection bitmap explains a red selected row before
scrolling; it does not by itself explain the red rectangle reported during
movement.

The source implementation makes the restore boundary more precise:

- `show_list_item()` calls `u_show_pic()` for the active-row bitmap;
- when the text needs scrolling, it calls `load_bgpic_sd()` with the same
  resource ID and switches to `CHAR_FILL_SDIMAGE`;
- `hori_scroll()` calls `clear_remain_pixel_sdimage()` for the one-pixel or
  remaining tail region;
- `clear_remain_pixel_sdimage()` reads the corresponding rectangle from the
  saved background image and sends only that rectangle through the LCD DMA
  path.

New M30 static evidence narrows the boundary without identifying its effect.
In `drv_ui.drv`, `FUN_260e4ff4` (file `0x9674`) passes `0x7c` (124) to the
ROM helper at `0xbfc09c01` (call at file `0x96be`). The same value is stored at
`+8` in text descriptors sent through the A17/B16-qualified provider path. The helper
body is absent, so the unit and operation remain unknown; `0x7c` is not proven
to mean 124 pixels or to equal the 205-pixel text viewport.

The new `run_drvui_marquee_dynamic_probe.sh` executes the actual B3 function with injected width-helper results. It confirms the `0x17` branch adds `0x7c` at descriptor `+8` and moves the emitted region X by `viewport - measured_width` when the injected width is shorter than the 120-unit region. The B16-qualified external target is stubbed; its renderer role is unconfirmed. The browser row-refresh callback and the `drv_ui` marquee path still have no direct call edge; the intervening listbox/framework step is missing. The probe therefore does not explain the device red strip, prove the folder uses B3, establish the hardware unit of `0x7c`, or identify the M30 scroll activation threshold.

The host contract in `tools/sim/listbox.py` models the SDK-style background
restore as an emulator behavior. It is not evidence that the M30 firmware
uses the same restore API. The folder emulator's `sdimage-tail` trace should
therefore be read as a modeled hypothesis, while the red initial selection
background is supported by the M30 resource data.

## M30-specific truncation point

### Browser list control and `common.sty` geometry

The stock and community `common.sty` files are byte-identical (SHA-256
`89ef11705f5014dd7a5f33cab7e93421a501daac7aa369c2722b136300577bb1`)
and parse as RU3 style control 6 at file offset `0xea5e6`. Its list geometry is
seven visible items at 30-pixel row spacing. The item record sets the text
area to `(x=35, y=37, width=205, height=16)` and selects active string mode 1
(`SCROLL`) while inactive mode is 3. These values come from the actual
resource bytes through `tools/inspect_sty_layout.py`; they are not inferred
from the public reference UI.

The browser code has a matching structural layout. `FUN_40cd4e00` initializes
the list through `FUN_48754574` and installs `FUN_487546d8` as a list-state
callback. `FUN_48754574` prepares seven 12-byte row descriptors, each pointing
at a `0x32`-byte name slot; `FUN_487546d8` fills those descriptors and records
the same `0x32` slot length. `FUN_40cd5218` has two separate callbacks:
`0x40cd52d8` points to `FUN_48714a20`, and `0x40cd52dc` points to the
`0x780db22d` dispatcher. The latter selects directory callback `FUN_71352820`
for fsel type 0. A p-code probe now executes this dispatcher and directory
callback from the refresh fallback under a synthetic list-state condition;
ordinary device browsing has not yet been shown to take that fallback. This
matches the style's seven visible
rows and 205-pixel text area. The 50-byte destination slots and 30-pixel
display rows are separate dimensions in the same list path. The filesystem
provider's output encoding and long-name handling remain unknown.

This is a structural match, not proof that the browser code passes a pointer
to this exact `common.sty` control. `FUN_48754574` initializes list state; it
is not the final drawing routine. No direct edge from the directory callback
to the `drv_ui.drv` B3 marquee entry was found, so the 205-pixel width alone
does not prove the browser's scroll threshold or explain the moving red
rectangle.

`browser.ap` function `FUN_48754574` contains four `li v0, 50` literals. They
define both the directory slot stride and the length passed onward to the
name-reading path. The browser and music applications each contain the same
`0x48754400` bank.

The caller supplies `0x40cd4c00` as the list buffer and `0x40cd4d90` as the
`path_info` argument. The code bank begins at `0x40cd4e00` and is only
`0x600` bytes, leaving a preceding `0x200`-byte page for this mixed data.
The important boundary is therefore not just the page end: eight 50-byte
slots end exactly at `0x40cd4d90` (`8 * 50 = 0x190`).

An initial 50 -> 64 experiment was generated for static inspection, but it
would overwrite `path_info` because `8 * 64 = 0x200`. It is unsafe and must
not be flashed. The same overlap means that a simple 50 -> 52 change is not
safe either; a real patch must relocate `path_info` (and prove every user of
that structure) or render the original name without this shared slot.

## Safety status

The experiment is deliberately kept opt-in in
`tools/patch_ui_name_capacity.py`. It is not included in the current safe HEX
and must not be flashed until the following checks pass:

1. repack/decrypt round trip and member-size/checksum verification;
2. path-info overlap check for title + seven rows;
3. a real-bank harness or QEMU memory test that writes adjacent slots and
   confirms listbox state is unchanged;
4. a physical test with short names, long ASCII names, UTF-16 names, folder
   entry/exit, and rapid selection movement.

The expected improvement of a future safe solution is specifically the
missing tail of long folder names. It does not change the red selection
bitmap or the underlying pixel-marquee routine, so any remaining red/black
residue after the name must be investigated separately in the `SDIMAGE`
redraw path.
