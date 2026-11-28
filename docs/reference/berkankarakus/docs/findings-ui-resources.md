# M30 UI resource boundary

This note records the safe, byte-level structure of the browser/main-menu
resource trees and selected RU3 style members. It does not claim that a node
ID or RU3 group index has a user-facing meaning unless that meaning is already
proven elsewhere.

## Reproducible check

```sh
PYTHONPATH=tools/emu python3 tools/emu/ui_resource_probe.py \
  --analysis-root analysis
python3 -m unittest tools/emu/test_ui_resource_probe.py
```

The two MCG members and four selected STY members are byte-identical in all
six local firmware extractions. Therefore the community firmware did not
change these menu/resource boundaries.

## MCG trees

`browser.mcg` is 0xa00 bytes and contains 17 contiguous node records. The
node IDs, in index order, are:

```text
0, 14, 5, 19, 1, 6, 15, 16, 20, 2, 8, 17, 18, 21, 3, 10, 11
```

`mainmenu.mcg` is 0x200 bytes and contains 3 top-level nodes with IDs
`0, 1, 2`. Both files carry the `ACTMCGGM01` marker and an `[INFO] filesum`
matching the parsed node count.

This gives the emulator a deterministic menu-tree input and prevents a UI
patch from silently changing node count/order. The host catalog also preserves
each node's raw `name=` bytes. Their encoding and user-facing localization are
not assigned, because those semantics belong to the matching resource/AP
lookup path.

## RU3 style members

All four selected STY files start with `RU3 0x19`. The header points to a
style table; the probe follows that pointer, validates each non-empty group
address against the member size, and stops at the first `(0, 0)` group. The
observed non-empty group counts are:

| Member | Size | Non-empty groups | Style table address / start |
|---|---:|---:|---:|
| `browser.sty` | `0x7800` | 1 | `0x7600 / 0x7610` |
| `config.sty` | `0x72800` | 2 | `0x72600 / 0x72610` |
| `udisk.sty` | `0x2f600` | 3 | `0x2f400 / 0x2f410` |
| `upgrade.sty` | `0x6200` | 3 | `0x6000 / 0x6010` |

The group addresses/counts are structural facts only. Item sizes and semantic
IDs must be supplied by the matching application/resource headers before any
resource-only patch is considered.

`upgrade.sty` is byte-identical across all six local corpora checked here. It
contains five 16x16 PicBoxes at `(72,185)`,
`(112,185)`, `(152,185)`, `(192,185)`, and `(232,185)`. Each has two real
bitmap frames: frame 0 is the unselected circle and frame 1 is the selected
circle. The two real text viewports are `(35,110,250,48)` and
`(85,40,150,16)`, both white. `tools/emu/upgrade_ui.py` exposes only the
selected-option state needed to preview this screen; it does not pretend to
execute the updater's AP input or file-validation state machine.

To render a selected-option preview without modifying firmware data:

```sh
PYTHONPATH=tools/emu python3 tools/emu/upgrade_ui.py \
  --style analysis/community/fw/upgrade.sty \
  --font analysis/community/fw/new_m.fnt \
  --selected 2 \
  --out /private/tmp/m30-upgrade-selected.ppm \
  --trace /private/tmp/m30-upgrade-selected.json
```

## Safe extracted-member UI candidate

The extracted-member candidate builder recognizes two observed `music.sty`
layouts and refuses all others. Eleven dated corpora use the standard layout;
`2024-04-24` inserts a 192-byte 8-pixel resource before the final transport
icons, shifting those icons from indices `29, 30` to `30, 31`. The four
transport payload hashes remain identical across both layouts, and the
150x150 missing-cover payload is identical as well.

The candidate builder verifies the missing-cover, transport-icon, and
`setting.sty` source hashes before changing bytes. A matrix run over all 12
persistent extracted corpora passed, with only `music.sty` and `setting.sty`
changed and with unchanged member sizes. This is strong cross-corpus evidence
for the resource boundary, but it is still an extracted-member candidate: it
is not a repacked or device-validated flashable firmware image.

## `music.sty` bitmap semantics

The resource table begins at `0x400` and has 32 entries of 16 bytes. Across
all 12 persistent corpora, every entry has format field low bits `0x0002`, a
non-zero width/height, and `size == width * height * 2` within the member.
There is no palette pointer or palette-sized side table in this resource
contract. The current evidence therefore supports direct little-endian
16-bit RGB565 words, with the exact meaning of format code `0x0002` kept as a
resource-format fact rather than a vendor enum name.

The host decoder in `tools/emu/music_bitmap.py` enforces that format and
geometry, rejects all other formats, and `NowPlaying.render()` uses resource
0 (the 150x150 album-art box) instead of drawing a synthetic placeholder. The
40x40 transport resources use the same direct-word contract. This makes the
emulator and visual patch preview consume the same bytes; it does not assign a
palette or LCD orientation transformation to `ALBUM.PIC`, which remains a
separate SD cache artifact.

## Consequence for the emulator

The emulator now loads the real MCG node list, opaque name bytes, and RU3 group
metadata as immutable `McgTree`/`Ru3Resource` fixtures through
`tools/emu/ui_resources.py`. The catalog exposes structural records to later
rendering code but does not decode localized names or assign user-facing
meaning to node IDs. Adding a new menu node or changing a style group is not
yet safe: it would require proving the corresponding AP lookup and preserving
the fixed member size/checksum contract.
