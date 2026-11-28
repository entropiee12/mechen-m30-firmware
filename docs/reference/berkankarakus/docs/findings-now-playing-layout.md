# Now-playing layout findings

The HTML prototype is now constrained by the resource layout instead of by a
generic 320x240 mockup.  The device's display is 320x240, and the current
music resource contains a 160-pixel boundary for the right-side information
area.  The metadata textbox width is approximately 129 pixels.

## Source-level path

The public US212A source maps the now-playing flow as follows:

1. `music_scene_playing()` obtains the album-art region from `ALBUM_ART_REGION`.
2. `play_scene_paint()` refreshes the artwork, track number, progress, EQ,
   shuffle, playback status, and ID3 information independently.
3. `play_scroll_init()` starts the title/artist/album state machine.
4. `play_scroll_deal()` passes `g_scroll_content[3]` to
   `ui_scroll_string_ext()`.
5. `ui_scroll_string_ext()` uses `ID3_SCROLL_TEXTBOX` and
   `ID3_MUSIC_PICBOX`; the horizontal pass is followed by a vertical pass and
   an idle delay.

The three content buffers are populated in this order:

```text
g_scroll_content[0] = title
g_scroll_content[1] = artist
g_scroll_content[2] = album
```

The firmware does not have a fourth dynamic metadata buffer in this path, so
`FILE` in the HTML is currently a design-only field.  Adding a live filename
line would require an application change, not just a bitmap resource edit.

## Resource controls

The read-only `tools/inspect_music_sty.py` parser reports the fixed control
groups and their offsets.  For the extracted community-compatible resource,
the relevant textbox records are 20 bytes each and are kept in-place so a
resource-only patch cannot resize the member.

Before changing a textbox record, the runtime use of that style ID must be
confirmed.  `ID3_SCROLL_TEXTBOX` is directly referenced by the scroll code;
the other textbox IDs are also used for album-art regions, lyrics, and browser
filename display.  They must not be repurposed globally.

The resource also contains the controls that the first host mockup had
previously drawn by hand.  The extracted `music.sty` records are:

```text
timebox[0]     x=70,  y=202, w=64, h=12
timebox[1]     right viewport embedded at x=235, y=202, w=64, h=12
progressbar[0] origin x=70, y=220
```

Resources `18..27` are the ten direct 8x12 digit glyphs, resource `5` is the
colon, and resource `36` is the slash.  Resource `17` is a 231x6 neutral
progress strip; resources `31` and `32` are one-pixel fill columns.  These
facts let the host preview reproduce the counter/progress geometry and native
small glyphs without inventing a font or placing the controls from the HTML
prototype.  The values and optional PicBox frame choices still come from an
explicit host state until the AP playback state machine is connected.

Bitmap inspection also assigns the four transport frames without changing the
resource member: PicBox 4 frames 0..3 are play, pause, next, and previous;
PicBox 1 frames 0..1 are shuffle-off and shuffle-on. The preview defaults to
the pause frame because its default state represents a currently playing
track. The supplied device photo identifies the global header state as PicBox
1 frame 1 (shuffle) and PicBox 0 frame 2 (repeat). The host preview draws
those from the real `music.sty` bitmaps, while leaving other PicBox 0 states
as explicit unresolved inputs.

The same inspection separates the embedded EQ indicator from the playback
progress bar: PicBox 3 frame 0 is the blank 124x24 bitmap and frame 1 is the
visible `EQ:` bitmap at its recorded position. The bottom progress bar is a
different `progressbar[0]` record and is rendered from resources 17, 31, and
32. PicBoxes 5..8 are fixed metadata glyphs (album, artist, title, and track),
so the host trace reports those roles instead of calling them unresolved.

The extracted `new_m.fnt` is also an Actions sparse-index font: 1024 ten-byte
presence entries begin at `0x10`, glyph records begin at `0x2810`, and this
M30 member uses 61-byte records with a final advance-width byte.  ASCII glyph
lookup is now implemented read-only in `tools/emu/m30_font.py`; the preview
renders the metadata at the native bitmap scale so the recovered glyph shapes
fit the 129-pixel TextBox viewports.  Each fixed 16-pixel row is clipped to
the glyph's trailer advance before drawing; using the full row made narrow
glyphs (notably `I`) overlap their neighbours.  This is a host-rendering
correction only: `new_m.fnt` is not changed and no flash patch is proposed.
The raw ASCII and lowercase records are not a consistent display raster
(punctuation such as `.` can also place ink outside its advance), so the host
preview now uses a complete, deterministic 5x7-to-2x metadata rasterizer for
printable ASCII.  The raw reader remains available for provenance.

## SDK-versus-M30 warning

The public SDK also contains `case/resource/music/music.ui`, which is useful
for understanding the editor's scene model.  Its generated `music.sty` is not
byte-compatible with the extracted M30 resource: the SDK has a different
number of picbox/textbox records and different control coordinates.  The SDK
source therefore explains the control structures and call flow, but its raw
coordinates must not be copied into an M30 HEX.

The M30 resource is the source of truth for a patch.  A candidate must preserve
its existing control-group counts and member size, and every changed style
record must be tied to a runtime style ID used by the M30 `music.ap`.

## Public SDK string-ID cross-check

The external [PD196_ATJ2127 SDK](https://github.com/Suber/PD196_ATJ2127)
reference tree (local checkout: `/Users/berkan/Projects/m30-inputs/ref/PD196`)
contains a useful, but non-authoritative, cross-check for the metadata table.
Its public resource headers define every value in the standard M30 table as a
string resource ID:

| M30 value | PD196 SDK symbol | Header |
| ---: | --- | --- |
| `0x8027` | `S_SECOND` | `case/resource/common/UIFile/common_res.h` |
| `0x004e` | `S_SET_MENU` | `case/resource/music/music_res.h` |
| `0x0024` | `S_GENRE` | `case/resource/music/music_res.h` |
| `0x0026` | `S_IS_OVERRIDE_FAVLIST` | `case/resource/music/music_res.h` |
| `0x0028` | `S_LICENSE_FAIL` | `case/resource/music/music_res.h` |
| `0x0017` | `S_CLASSIC` | `case/resource/music/music_res.h` |
| `0x005d` | `S_THIS_ALBUM` | `case/resource/music/music_res.h` |
| `0x001a` | `S_CUSTOM` | `case/resource/music/music_res.h` |

This exact eight-value correspondence raises the confidence that the M30
payload's first halfword is string-ID-like rather than a palette, bitmap, or
TextBox index.  It is still an educated guess: the SDK is a different build,
its generated `music.sty` is not byte-compatible with M30, and the M30
`0x004f`/`0x005e` revision values do not preserve the standard table.

The same SDK also narrows what this correspondence can mean.  Its music
playing code fills `g_title_buf`, `g_artist_buf`, and `g_album_buf`, then points
`g_scroll_content[0..2]` at those buffers; it does not use the resource IDs as
the ID3 title, artist, or album values.  The table therefore cannot currently
be labelled as the M30's visible metadata-field names.  At most, the public
headers show that the halfwords fit an existing string-resource namespace.

The same SDK documents a 12-byte `string_desc_t` containing a data union,
language byte, length, argv, and result fields.  The M30 probe has only proved
that its metadata payload contains a little-endian halfword at `a0 + 0x10`, the
copied byte at `a0 + 0x12`, and a zero word at `a0 + 0x14`.  The observed
payload is therefore not promoted to `string_desc_t` or any other named ABI.
The emulator continues to expose `table_value` as opaque, while this section
records the strongest current semantic hypothesis and the evidence needed to
revisit it.

The public SDK's `textbox_private_t` is a closer structural comparison:
`uint16 str_id`, `int8 lang_id`, `uint8 reserve`, a pointer-sized word, and a
color word.  A sentinel-backed M30 p-code probe now checks the corresponding
first 12 payload bytes in the community, stock, and 2024-04-24 corpora.  The
firmware writes the table halfword at `+0x00`, the copied byte at `+0x02`, and
zero at `+0x04`.  The byte at `+0x03` and word at `+0x08` retain the probe's
`0xa5` sentinel, so they are not initialized by this path.  This is useful
layout evidence, not proof that M30 uses the SDK type: the zero word has not
been observed at a renderer sink and cannot yet be called a string pointer or
color.

The same public header also defines `style_infor_t` (`uint16 style_id` plus a
resource type) and `picbox_private_t` (`uint16 pic_id`, `uint8 frame_id`, and a
reserve byte).  This gives the control payload a useful structural hypothesis:
the M30 `op=0x02` `a0` bytes look like a style record, while `a1=a0+0x08`
looks like a PicBox-private record.  The observed `0xffff`/`0xff00` halfwords
and `0`/`1` bytes fit that shape, but their exact `pic_id` and frame semantics
are not assigned.  Likewise, the `op=0x03` `a1=a0+0x10` payload resembles the
public `textbox_private_t`: the table value could be `str_id`, the copied byte
could be a language-like field, and the zero word could be a null string
pointer.  The untouched `+0x08` word prevents promoting the whole record to
that SDK type; these remain structural hypotheses only.

`tools/emu/ui_dispatcher.py` now carries the verified payload fields as
`OpaqueMetadataPayload` in its trace.  The host dispatcher deliberately does
not render this payload or mutate the framebuffer; this preserves the observed
ABI boundary until the real metadata renderer or LCD sink is identified.

## M30 binary cross-check

The community-compatible M30 `music.ap` was loaded into Ghidra using the
ATJ2127 bank table and decompiled into 819 functions.  The main refresh path
is `FUN_40154ddc` at virtual address `0x40154ddc`.  It confirms three fixed
screen bands before redrawing the controls:

```text
header:     x=0, y=0x00, w=0x140, h=0x1c
content:    x=0, y=0x1c, w=0x140, h=0x97
footer:     x=0, y=0xb3, w=0x140, h=0x3d
```

The same path uses `0x1004` for the album-art picture box and `0x2005` for a
dynamic text draw.  `FUN_40114f20` clears a 150x150 content area at
`(0, 0x1c)` before handing the artwork/data to the UI dispatcher.

The paint branch's control sequence is also useful for the host preview:
`0x1002` (artwork), `0x1003` (EQ), `0x1004` (transport), and `0x1005` through
`0x1008` (the four metadata glyphs) are emitted by `FUN_40154ddc`.  The
resource still contains PicBoxes 0 and 1, but this refresh function does not
select their controls. The host preview models the observed global header
overlay separately, using those two real `music.sty` frames and TextBox 5 for
the EQ label. Header status assets are real RGB565 resources from `common.sty`:
the speaker is resource 3 at `(3,2)`. The supplied device photo's card/play/
battery state matches PicBox/frame pairs `(2,2)`, `(3,2)`, and `(1,6)`, which
resolve to resources `35`, `2`, and `31` at their recorded PicBox positions
`(247,0)`, `(267,0)`, and `(287,0)`. These replace the earlier manually
selected resources/positions in the host preview. The resource references
carry a high-bit flag, which the host strips before bitmap lookup. The
photo-backed frame choices are now explicit host inputs; the exact AP status
selection path remains unresolved.

The metadata glyphs are part of the stable row layout in the host model and
replace the literal label text at a common x alignment; the value viewports
remain the real `x=190` TextBoxes. The transport control is transient in the
host model: the firmware emits it from the `param_1 & 0x8` / `param_1 & 0x20`
refresh branches, so the stable playback preview keeps it hidden by default
and exposes it through `--show-controls`.

The same sentinel-backed probe also resolves the initialized part of the
secondary control payload at `a1 = a0 + 0x08`. For controls `0x1000` and
`0x1001`, its first halfword is `0xff00`; for `0x1002`, `0x1003`, and `0x1004`,
it is `0xffff`. The following byte is `1` only for `0x1003` and `0x1004`, and
`0` for the other three. The next byte and following word retain the `0xa5`
sentinel. The host records these bytes through `control_payload_trace()` but
does not assign them palette, bitmap, or state semantics.

The style record's byte at `a0 + 0x02` is stable across all twelve dated M30
images: it is `1` for control `0x1003` and `0` for every other observed
control/text ID, including `0x1004` and `0x2005`. This fits the public SDK's
`UI_AP=0` / `UI_COM=1` shape, but the host carries the value only as the raw
`style_resource_type` field; it does not promote the structural match to a
full resource-type semantic.

`run_now_playing_state_probe.sh` emits a compact `PAYLOAD TYPES` line with the
observed control/text ID to raw-byte mapping, so version differences remain
reproducible without retaining temporary Ghidra logs.

There is also negative evidence against using that byte to redirect the host
to `common.sty`: the extracted M30 `common.sty` PicBox IDs are in the
`0x8000`-range and do not contain direct `0x1003` or `0x1004` entries. The
`0x1000 + PicBox index` relationship is therefore retained as an M30
dispatcher convention, independently of the public SDK's struct shape.

The first call-site candidate changed the `0x2005` literal in this paint path,
but the device-visible marquee is emitted by the shared scroll renderer, not
by that call alone.  Ghidra identifies the actual draw path as:

```text
FUN_40d54f2c
  0x40d55078: li v0, 0x2005
```

This is the call that consumes the active title/artist/album scroll item.  The
new candidate changes this renderer call to `0x2001`, while leaving the
unrelated `FUN_40154ddc` literal unchanged.

The call-site audit found an important boundary: `0x2005` is also referenced by
`FUN_40d54f2c`, the shared scroll renderer.  Therefore changing the shared
`0x2005` textbox record is not screen-local.  The current isolated candidate
leaves that record unchanged and patches the immediate in `FUN_40d54f2c`:

```text
virtual: 0x40d55078
file:    music.ap 0x27878
before:  04 f0 05 6a   (li v0, 0x2005)
after:   04 f0 01 6a   (li v0, 0x2001)
```

`0x2001` is already the existing right-panel textbox geometry.  This keeps the
other `0x2005` caller on its original style while redirecting the now-playing
draw to the verified right-panel record.

This explains why the public SDK coordinates cannot be copied directly and
why the first firmware UI patch must target the M30-specific style record
`0x2005`, while leaving the scroll, artwork decode, and playback code intact.

## Prototype status

[`now-playing-ui.html`](now-playing-ui.html) uses the 160-pixel panel boundary,
129-pixel text width, red accent, and compact spacing.  It intentionally keeps
the `FILE` line as a visual placeholder until the application path is mapped.

The current firmware step is therefore the isolated scroll-renderer candidate.
It changes one byte in `music.ap` and carries the already-tested now-playing
`music.sty` artwork/icon resources; it does not modify `mengine.ap`, browser,
indexer, or settings code.  The earlier paint-path-only candidate did not
affect the visible marquee and is retained as an offline comparison only.

## Dynamic paint boundary

`tools/ghidra/run_now_playing_paint_probe.sh` executes the real MIPS16
`FUN_40154ddc` from `music.ap` in Ghidra's p-code emulator. It redirects only
the verified UI-dispatch literal at `0x40155324` and the zeroed state-flags
pointer at `0x40155334` to temporary RAM; the AP bytes are not modified and no
device image is produced.

The minimal initial-paint case (`flags = 0x1`) reaches the UI dispatcher seven
times and passes the real return path. The exact call envelope is stable across
all twelve dated `music.ap` images: `0x18c3`, a `0x12` clear payload,
`0x39e7`, a second `0x12` clear payload, `0x18c3`, a third `0x12` clear
payload, and the artwork control payload whose control ID is `0x1002`.
This is the first dynamic contract for the UI work. It separates the
now-playing geometry path from metadata scrolling, playback state, and artwork
decoding.

`tools/ghidra/run_now_playing_paint_corpus_probe.sh` repeats this initial-paint
check over every dated `music.ap` below the persistent evidence root. The
current corpus result is `images=12`; all images produced the same seven-call
envelope and real return boundary. The wrapper rejects Ghidra script errors
even if the process itself exits successfully.

The host JSON now keeps this boundary in `paint_dispatch`. The older
`observed_dispatch` field remains the nine-call shared-scroll trace, including
controls `0x1012`/`0x1013`, and is intentionally not used as evidence for the
initial paint. This distinction prevents the host preview from presenting a
scroll-renderer call as an artwork paint call.

The host JSON also exposes `state_dispatch` for the five separately probed
state branches: metadata (`0x100`), transport/EQ (`0x20`), status-low
(`0x200`), status-high (`0x800`), and controls (`0x08`). It records only the
verified operation and raw identifier order. The identifier's visual state is
left unresolved because the p-code probe stops at the dispatcher and does not
prove which runtime frame or resource state the consumer selects.

The first eight bytes of each `0x12` clear payload are now decoded as a
rectangle header, not guessed coordinates: `(x, y, width, height)` is
`(0, 0, 320, 28)`, `(0, 28, 320, 151)`, and `(0, 179, 320, 61)`.
The three regions tile the complete 320x240 display. The remaining payload
bytes still contain unresolved pointers/state and remain opaque in the host
model. The paint probe asserts this header and the seven-call ID sequence on
each dated image.

The static decompile of `FUN_40154ddc` independently names these writes as
`uStack_18`, `uStack_16`, `uStack_14`, and `uStack_12`, then passes
`&uStack_18` to operation `0x12`. It does not write the bytes that appear
after this eight-byte header in the probe dump during the initial-paint branch;
those bytes are adjacent stack locals, not established rectangle fields.
`tools/ghidra/run_now_playing_paint_decompile_probe.sh` keeps this decompiler
shape as a narrow, reproducible guard.

The public SDK gives a useful operation cross-check: its UI enum assigns
`0x12` to `UI_CLEARSCREEN` and `0x14` to `UI_SET_BACKGD_COLOR`, and both macros
use the same four-argument dispatcher envelope. The M30 host model therefore
applies `0x18c3`, `0x39e7`, and `0x18c3` as the three band colors before their
respective rectangle clears. This remains a cross-build ABI hypothesis until
the M30 resident consumer is directly decompiled; the color words and band
geometry themselves are recorded from the M30 call trace.

This is deliberately not a full LCD emulator. It does not claim that a font,
resource, or artwork decode succeeded; it proves only the real paint entry's
control-dispatch sequence and return boundary. The next high-value extension
is to feed synthetic metadata/artwork state and trace the `0x100`, `0x200`,
`0x400`, `0x800`, and `0x20` branches one at a time.

The follow-up `tools/ghidra/run_now_playing_state_probe.sh` now executes and
checks the `0x100` metadata, `0x20` transport/EQ, and `0x200`/`0x800` status
branches in addition to the initial paint. The observed control IDs are
stable across the stock, community, and V1.101.11 `music.ap` corpora. This
does not prove that the final pixels are correct, but it proves that the
candidate UI work can be isolated at the dispatcher boundary without
entering the decoder or DAC path.

The same probe now dumps the real stack payload bytes and asserts their
pointer layout in the community and stock APs. For operation `0x02`, the
payload's first little-endian halfword is the control ID and the `a1` argument
points to payload offset `+0x08`. For the metadata operation `0x03`, `a1`
points to payload offset `+0x10`, and the word at that offset is identical to
the word reached through `a1`. These are payload-layout facts only; the words
at the pointed-to offsets are not yet assigned palette, bitmap, or text
pointer semantics. They are intentionally kept separate from the shared
scroll renderer's `0x1012`/`0x1013` payload trace.

The metadata branch's decompile adds a narrower boundary. The literal at
`0x40155338` points to `0x4015535c`, where the firmware stores eight little-
endian 16-bit values:

```text
index:  0       1       2       3       4       5       6       7
value:  8027    004e    0024    0026    0028    0017    005d    001a
```

`state + 0x19` selects one of these values for the metadata payload at
`a0 + 0x10`; `state + 0x0e` is copied to `a0 + 0x12`, and `a0 + 0x14` is
explicitly written as zero in the isolated path. The `metadata-table` p-code
case walks all eight indices and injects `0x5a` into `state + 0x0e`. The reusable
`tools/ghidra/run_now_playing_corpus_probe.sh` wrapper now runs that check
against all twelve dated `music.ap` images; all twelve images pass all eight
indices, including the initialized-field and sentinel-preservation assertions.
A corpus scan found the standard table
in ten images. The `2024-04-24` AP is a real revision boundary:
indices 1 and 6 are `0x004f` and `0x005e` instead of `0x004e` and `0x005d`, and
the p-code probe emits those changed values. The host model therefore reads
`metadata_table_values` from each AP and exposes `table_value` with opaque
semantics. They are not TextBox IDs, palette entries, decoded characters, or
bitmap indices.

The dynamically observed paint IDs `0x1000` through `0x1004` also match the
host resource convention `0x1000 + PicBox index`: status-low/high, artwork,
transport, and EQ each select the corresponding `music.sty` PicBox. The
new `controls` case (`flags=0x08`) executes the real sequence
`0x1007, 0x1006, 0x1008, 0x1005, 0x1004` (with the surrounding `0x1002`
artwork refresh); this dynamically confirms the same convention for the four
metadata-glyph PicBoxes as well. Those four calls use operation `0x02` with
`a1=0`, unlike the stateful controls that carry the secondary payload pointer.
The `0x2005` text style remains a separate opaque dispatcher style; it is not
being equated with a `music.sty` TextBox index.
