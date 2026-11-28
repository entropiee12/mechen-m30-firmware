# Folder-name marquee: static binary findings

This is an offline analysis of `drv_ui.drv` from stock V1.101.10 and
community V1.292.00. The files are byte-identical (SHA-256
`011f4e6a50738026ac641370f493c5df2e59adc435871d8a213e717604d2576a`). It is
not a device or real-LCD test.

The same SHA-256 and the same 24-entry bank layout were also observed in the
archived `v1.101.11`, `v1.101.12`, `v5`, and `first-pass-verify` images. That
means a UI-only fix targeting this driver would not distinguish those
firmware generations; the behavior difference seen on-device must come from
the caller/resource path or from another image component.

## What is confirmed

### Name preparation: `FUN_30009070`

- The text pointer is read from the list descriptor.
- The routine scans at most `0x66` bytes (102 bytes) for a terminator.
- On a terminator it writes two spaces and a second terminator before the
  marquee wraps.
- The browser callback provides a 50-byte destination slot and requests 25
  UTF-16 units from the filesystem helper. Whether that helper truncates or
  transforms long names is unknown.

The host contract for this preparation step is `prepare_marquee_utf16()` in
`tools/sim/listbox.py`. Its tests preserve the byte-level boundary, leading
BOM removal, and the two-space gap without implying that the proprietary
font or LCD renderer has been emulated.

The relevant instructions are at flat addresses `0x300090e0`–`0x3000914e`:
the `sltiu ... 102` bound, the terminator test, and the three writes that add
the two-space gap.

The maintained flat-map decompile confirms the preparation details from the
real `drv_ui.drv`: in UTF-16 mode the routine walks at most `0x66` bytes in
two-byte units, appends two spaces and a UTF-16 terminator, then skips a
leading `FF FE` marker before handing the buffer to the scroll state. Non-
UTF-16 input is routed through a separate resource/string helper instead of
being widened here.

### Marquee state update: `FUN_30009674`

- The routine receives a scroll descriptor and reads the current text/layout
  state through indirect helper pointers.
- It updates 16-bit layout/state fields in its stack descriptor, including the
  fields later used as the current horizontal position and remaining width.
- The update path uses measured geometry and does not advance by complete
  characters. The existing simulator's old character-step model was therefore
  misleading; it has now been supplemented with a pixel-offset model.
- Several draw/measure/clear operations are indirect calls. The function does
  not contain an obvious full-viewport fill inline, so the red rectangle is
  most likely in one of those helpers or in the selected-item background path.

The new Ghidra flat-map decompile adds three useful constraints:

- the initial resident system-operation call receives `(buffer, 0, 0x7c, 1)`.
  Its shape matches the known copy/fill service's `(destination, value,
  length, operation)` form, making a fill of `0x7c` bytes the leading
  interpretation. The ROM stub body is unavailable here, so this remains an
  inference; `0x7c` is not evidence for a 124-pixel region;
- independent raw MIPS16 disassembly pins this call to `drv_ui.drv` file
  `0x96b4`: it loads destination `0x9fc0bd2c` and target `0xbfc09c01`, sets
  `a3=1` and `a1=0`, then uses the `li a2,124` instruction in the `jalr`
  delay slot. The literal pool is at file `0x9938` (`2c bd c0 9f 01 9c c0 bf`).
  `drv_ui.drv` stock/community SHA-256 is
  `011f4e6a50738026ac641370f493c5df2e59adc435871d8a213e717604d2576a`;
  `python3 tools/emu/drvui_clear_call_probe.py analysis/community/fw/drv_ui.drv`
  reproduces these byte and pointer checks. Its test also pins the stock copy
  and rejects changed instruction, pool, or stub bytes. The probe reports the
  service effect as unverified; it does not promote the fill hypothesis.
- a headless rerun of `run_drvui_runtime_probe.sh` confirmed that the indirect
  call pointer at runtime address `0x260e52bc` is `0xbfc09c01`. The extracted
  `drv_ui.drv` also contains the raw module-`0x10` syscall stub bytes
  `10 6b 05 e8` at file offset `0x1200`, followed by the module-`0x0f` stub.
  The resident call target's body is not in that extracted bank, and no edge
  ties it to the local module-`0x10` stub. Thus the raw stub bytes do not settle
  whether this call fills `0x7c` bytes;
- the same `0x7c` value is stored in the temporary region descriptor passed to
  later text/background helpers. Whether this is a descriptor byte size or a
  field consumed as geometry is unresolved; do not label it a pixel width;
- the routine consumes two 8-byte string records per pass (`param_3` selects
  the first record and the next record is selected from the state byte),
  measures each record, and then draws it through separate indirect helpers;
- when measured width is smaller than the available width, the code adjusts
  the x origin by the remaining width before drawing. This could leave a stale
  right edge if the helper's background restore is incomplete, but the missing
  provider prevents attributing the red artifact to that cause.

The public reference implementation gives one possible comparison for that
indirect path: its `clear_remain_pixel_sdimage()` restores the remaining
rectangle from the saved selected-row bitmap before issuing LCD DMA. No M30
function-pointer match establishes that it uses the same operation.

The helper addresses are runtime-initialized resident pointers, not file
literals, so their exact LCD/resource semantics are still unresolved. The
resident helper at `0xbfc09e34` is now also decompiled: it conditionally
forwards a three-argument call to the raw target stored at `0xbfc09e70`
(`0x164490a1`, candidate A17 + `0xa1`) only for the relevant display-mode
range and state. A17 is absent from the extracted M30 driver, so this target
must remain an unresolved external/provider entry; the forwarder is not the
clear implementation. The host test now explicitly records that absence. The
B3 runtime probe now also binds the literal envelope: the initial system
operation is the resident wrapper `0xbfc09c01`, the three-argument background
operation goes through the conditional resident forwarder `0xbfc09e35`, and
the text-side SD-image call target is the B16-qualified pointer `0x26409401`.
These are exact pointer values, not yet names for the underlying LCD/resource
operation. The resident probe asserts the forwarder shape. The flat loader used for this result is
`tools/ghidra/FlatLoad.java`; the complete temporary decompile was generated
from the stock/community-identical `analysis/community/fw/drv_ui.drv`
without modifying it.

### B3 marquee branch: p-code execution with measured-width inputs

`bash tools/ghidra/run_drvui_marquee_dynamic_probe.sh` executes the extracted
`FUN_260e4ff4` instructions from a hash-pinned `drv_ui.drv`. It runs with a
synthetic 120-unit region and injected width-helper results (80 and 180). The
branch byte at `0x9fc0be9d` is set to `0x16` or `0x17`; the `0x164c9001` width
helper, resident UI calls, Arabic transforms, resident forwarder, and B16-qualified
external target `0x26409401` are stubbed. No pixels or LCD effects are inferred.

Observed result: for `0x16`, the real B3 path makes no width-helper call and
sends descriptors without the `0x7c` field. For `0x17`, it calls the width
helper twice and sets descriptor offset `+8` to `0x7c` on both calls through `0x26409401`.
With injected width 80, the first returned region's X coordinate moves from 5 to 45
(`120 - 80`); with injected width 180, it remains 5. The B16-qualified target
is not present in this extracted driver, so the probe only records its call
arguments; its renderer role is not executed or confirmed. This establishes B3's response to injected branch state and width;
it does not establish that the browser folder callback invokes B3, what `0x7c`
means in hardware coordinates, or why red residue appears.

### B3 trailing table: local B08 renderer is a separate path

The adjacent table after the second B3 function contains `0x26209401`, not
`0x26409401`. The former maps through the public bank formula to the local B08
candidate `0x26225400`; the latter is the separate B16-qualified pointer used
by the marquee descriptor path and has no payload in the extracted M30 driver.
Conflating these two values would incorrectly turn a local candidate into a
claimed B16 recovery.

The B08 candidate decompiles to a descriptor/line renderer. Its observed shape
is useful but narrower than a complete provider proof: it reads a short string
descriptor, normalizes the `~` marker, derives alignment/clipping flags from
the descriptor, clamps rendered width to the available width, computes the
remaining-space offset for centered text, and submits the final region through
an indirect renderer helper. This confirms that width/clipping decisions exist
in a local renderer, but it does not identify the image-backed tail restore
operation. The B08 mapping and decompile are reproducible with:

```sh
bash tools/ghidra/run_drvui_b3_table_probe.sh analysis/community/fw/drv_ui.drv
bash tools/ghidra/run_drvui_b3_candidate_probe.sh analysis/community/fw/drv_ui.drv
```

The red folder-name artifact therefore remains split into two independently
testable paths: the local B08 renderer can account for clipping/alignment
behavior, while the missing B16/A17 provider chain is still required to prove
the image-backed tail restore that should remove the stale red rectangle.

### Dated-corpus forwarder cross-check

As a hypothesis test for the red marquee rectangle, the resident probe was
run with the nine hash-verified dated `drv_ui.drv` files available outside
Git. All nine passed with the same resident boundary:

```text
DRV_UI RESIDENT PASS syscall=bfc09c00 mode_forwarder=bfc09e34 state_setters=bfc0a8bc,bfc0a8c8
```

This makes the shared forwarder/state-setter envelope a stable cross-version
fact. It does not resolve the external A17 provider at `0x164490a1`, so the
“incomplete background restore” explanation remains a medium-confidence
hypothesis rather than a patch justification.

### Real `music.ap` scroll probe

The Ghidra p-code probe was run against the persistent
`2025-06-26/fw/music.ap` copy with generated MIPS stubs. The firmware AP was
not modified. The full renderer passed after 359 emulator steps and exposed
this dispatcher order:

```text
clear-color, clear-rect, clear-color, clear-rect,
draw-control(0x1012), draw-control(0x1013),
draw-text(style 0x2005), draw-dynamic(a2=6), draw-text(style 0x2001)
```

The event entry point independently passed all three tested modes: `next`
produced `active=1, previous=0, status=2, y=40`; `previous` produced
`active=2, previous=0, status=2, y=40`; and `redraw` produced
`active=0, previous=0, status=1, y=50`. The host dispatcher replay now keeps
this exact call order, while event state and unresolved LCD/resource helper
semantics remain separate.

### Text alignment: `FUN_30008600`

The existing static finding remains: for multi-line centered text the routine
computes the x-coordinate from the pre-line-break `true_length`, then calls the
word/line splitter without recomputing x. This explains why a first line can
look left-aligned while a later line is centered. It is a separate issue from
the folder-name truncation.

## Current conclusion

### Host clipping comparison

The host model records renderer clipping separately from an optional
slot-layout assumption. The binary proves a 50-byte destination slot and a
25-unit request to the filesystem helper, but not the provider's output
encoding, terminator policy, or long-name handling. The preview's 46-byte
payload (23 BMP characters) assumes a two-byte BOM and terminator; it is not
evidence that the device loses the name at that boundary. Provider output is
the default input to the host marquee.

Now-playing uses the full metadata value and clips it at the real 129px
TextBox. The same long value is therefore marked `renderer-viewport` and
remains eligible for scrolling. The comparison can be reproduced without a
device using the existing folder and now-playing renderers; their JSON traces
now expose `clip_stage`, both measured widths, and the scroll input source.

The folder renderer has two host-only inputs: `--scroll-source provider-output`
uses the supplied text as returned by the filesystem provider, while
`--scroll-source slot-assumption` applies the candidate BOM-and-terminator
reserve. The latter is a visualization hypothesis, not a recovered firmware
truncation rule.

The lowest-risk fix is not to change the four `50 -> 52` length immediates.
The browser's shared buffer is still only statically proven to be 50-byte
slots, and enlarging the lengths without moving the buffer can overwrite the
next item. The next binary investigation must resolve the indirect helpers
used by `FUN_30009674`, especially the clear/background operation, and then
find whether a larger name can be rendered directly from the filesystem
without using the truncated slot.

### New static lead: 64-byte slots

The bank map gives a stronger layout clue, and also rules out the first naive
expansion: the caller passes `0x40cd4c00` as the list buffer and
`0x40cd4d90` as `path_info`. Eight 50-byte slots end exactly at that second
address. Although eight 64-byte slots fit the surrounding `0x200` page, they
would overwrite `path_info`. The controlled experiment is documented in
[`findings-scroll-reference.md`](findings-scroll-reference.md); it remains
outside the safe candidate and is now explicitly marked unsafe.

## Next investigation

The former “map the indirect runtime pointers” step is now covered by the B3
candidate probes and the browser AP name probe. The exact browser callback
entry points and their copy limits are reproducible with:

```sh
bash tools/ghidra/run_browser_name_probe.sh analysis/community/fw/browser.ap
```

The refresh-to-row-format path is now joined in one p-code run: the real
`FUN_40cd5218` refresh invokes `FUN_48714a20`, which reads its 20-byte row
record and writes the converted text into the row's 50-byte name slot. The
provider and converter remain synthetic leaves, and the probe stops before
the list paint callback. See [`findings-browser-marquee-bridge.md`](findings-browser-marquee-bridge.md)
for the executed result and the next framework boundary.

The remaining high-value work is:

1. Resolve the provider behind the conditional resident forwarder
   (`0x164490a1`) and the B2/B16 far-call candidates used by the descriptor
   envelope; only that mapping can distinguish a solid-color clear from an
   image-backed restore.
2. Capture the framework/list paint callback that consumes a formatted row
   slot, then follow its active-string descriptor into the driver marquee
   updater. The browser AP has no direct call edge to B3, so the callback or
   runtime function pointer is the missing bridge.
3. Resolve ownership of the list buffer and adjacent `path_info`; only then
   consider a binary patch. Any candidate must show that adjacent list entries
   and listbox state remain intact.

## Driver bank layout recovered

The `yqhx` header is not just a signature.  For `drv_ui.drv`, header words at
`0x48`, `0x4c`, and `0x50` point to the file starts of the A/B/C overlay
groups: `0x1600`, `0x6600`, and `0x10600`.  The tables at `0x80`, `0x100`, and
`0x180` contain pairs of byte-order 16-bit `(start16, end16)` offsets
relative to those group starts.  The non-zero entries describe ten A banks of
`0x400` bytes, ten B banks of `0x800` bytes, and four C banks of `0x400`
bytes.

There is one useful boundary detail: `FUN_30009674` begins at file offset
`0x9674`, while the fourth B window starts at `0x9680`.  The 12-byte lead-in
is consistent with a bank entry/prologue kept immediately before the payload
window; it is why a simple “function offset must be inside the table range”
check would incorrectly discard the real marquee routine.  The public SDK bank
formula maps B3 to `0x260e5000`; remapping the B3 bytes at that address gives
the marquee entry at `0x260e4ff4`, which decompiles cleanly and retains the
`0x7c` clear call and the two-record text path. This is strong static
confirmation, not a physical-device trace. See
[`findings-kernel-loader.md`](findings-kernel-loader.md).

This explains why the earlier flat map was useful for decompilation but not a
runtime address map: the `0x30000000 + file_offset` names are analysis
coordinates.  The bank payloads are overlaid at their linker-selected
addresses after the driver loader has installed the resident image.  The
structure is now decoded by [`tools/inspect_drv.py`](../tools/inspect_drv.py),
which deliberately reports file ranges first and only prints the public SDK
runtime comparison addresses when `--runtime-base` is requested.

For the public ATJ2127 linker layout, the bases are A
`0x16024c00`, B `0x26025000`, and C `0x36021a00`, with `0x40000` spacing per
bank index. The M30 `kernel.drv` loader and the public linker description now
agree on the driver format and bank geometry, so these are high-confidence
M30 candidates. They are still not a direct hardware observation; the
indirect bank-window lookup must be resolved before using an exact callable
address in a patch.
