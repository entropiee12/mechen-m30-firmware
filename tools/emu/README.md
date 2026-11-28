# Ghidra-guided M30 UI emulator

This is the first layer of the hybrid emulator. It is intentionally small and
deterministic:

1. `FirmwareTrace` parses the real `music.ap` bank table, checks the
   Ghidra-audited shared marquee call at `0x40d55078`, and resolves the
   literal-pool UI/timer pointers around `0x40d5516c`.
2. `MusicStyle` parses the real M30 `music.sty` RU3 control records.
3. `music_bitmap.py` validates the 32-entry `music.sty` resource table and
   decodes its direct format-`0x0002` RGB565 bitmap words.
4. `m30_font.py` reads the sparse-index `new_m.fnt` font without modifying it;
   the now-playing preview keeps those raw records for provenance and uses a
   deterministic display-only ASCII rasterizer so broken lowercase and
   punctuation records cannot distort the UI.
5. `NowPlaying` replays the UI boundary as a trace and renders a 320x240 PPM
   framebuffer using the real textbox geometry, the real album-art bitmap,
   and RGB565 colors.
6. `lcd_preview.py` sends that rendered frame through the recovered
   `0x36/0x2a/0x2b/0x2c` LCD sink and writes a visible 320x240 PPM plus a
   command/pixel trace. This is the hybrid screen preview; it is not a full
   ATJ2127 boot or physical LCD emulation.
6. `run_lcd_preview.sh` runs the real LCD QEMU probe first and only then
   writes that hybrid preview, keeping the visible artifact tied to a passing
   real-code LCD boundary.
7. `run_qemu_dac_probe.sh` executes the exact resident `drv_dac.drv` payload
   with a translated MIPS16 helper-call alias and a synthetic GPIO context. It
   verifies the byte-transfer boundary across all six local DAC driver
   corpora; `tools/ghidra/run_dac_probe.sh` additionally checks the decompiled
   I2C-shaped START/STOP, MSB-first, and ACK/NACK call sequence. Neither probe
   emulates codec registers or audio output.
8. `run_qemu_usound_chain_probe.sh` runs the real producer and ring consumer
   with two distinct firmware ring literals aliased to one guest address. It
   verifies a seeded 16-byte pattern through the producer and into the
   consumer callback source, plus state/control flow and the module-`0x10`
   trap. The consumer destination copy and natural firmware ring connection
   remain unresolved; PCM/DAC ownership is not modeled.
9. `scroll_descriptor.py` models the verified state/event offsets used by
   `FUN_40d54f2c` and `FUN_40d54d80`.
8. `ui_dispatcher.py` provides the conservative host shim for the verified
   dispatcher target `0xbfc16405`.
9. `make_qemu_probe.py` prepares a non-firmware ELF probe that calls the real
   MIPS16 renderer with only the audited literal pointers redirected to test
   stubs. Its MIPS32-to-MIPS16 entry uses an architectural `JALX` trampoline,
   and its Linux-user stack/null shims reproduce the Ghidra harness without
   mapping guest address zero.
9. `tools/ghidra/run_scroll_probe.sh` executes that same real-bank renderer in
   Ghidra's MIPS p-code emulator. `run_qemu_probe.sh` executes the equivalent
   user-mode ELF when a MIPS QEMU binary is available (the current macOS setup
   uses the Fedora container for that step); neither path models an ATJ2127
   SoC boot.
10. `driver_banks.py` parses the real `drv_ui.drv` overlay table and exposes
   far-pointer payload candidates without treating every candidate as code.
   It preserves the raw B2 `0x471` provenance while exposing its aligned
   MIPS16 entry offset `0x470`; the unresolved B2 `0x4e9` entry remains
   separate from that validated path.
10. `tools/ghidra/run_kernel_exception_probe.sh` checks the extracted
   `ker_xsys` exception entry and records the boundary where the unavailable
   fixed handler begins.
11. `yqhx.py` parses the resident driver's four segment descriptors, BSS range,
   entry fields, and the kernel's mode-dependent slot layout. It is a load
   plan only; it never executes raw payload bytes.
12. `driver_loader.py` models the verified `yqhx` header/segment materializer.
   Its synthetic `a408`/`a578` slot cases are kept as hypotheses and must not
   be confused with the similarly named M30 resident-table helpers.
13. `provider_scan.py` reproduces the negative A17/A19/B16 provider scan across
   extracted `.drv` members. It reports byte occurrences only and does not
   infer executable ownership from a match.
14. `tools/ghidra/run_drvui_pointer_probe.sh` maps the complete UI driver as
   a static MIPS16 corpus and reports each observed far-pointer literal with
   its containing Ghidra function when one is known.
15. `tools/ghidra/run_drvui_b2_probe.sh` remaps the real B02 bank and checks
    the paired `+0x471`/`+0x4e9` candidates: the former decompiles from the
    aligned MIPS16 address while the latter terminates as bad instruction
    data.
16. `bank_runtime.py` models lazy bank selection and load events. It only
    permits explicitly validated entry offsets to become executable; an
    unresolved candidate is recorded but never called. Load events preserve
    both the raw pointer offset and the aligned MIPS16 entry offset.
17. `tools/ghidra/run_drvui_pointer_cluster_probe.sh` groups the 16 observed
    far-pointer literals by nearby file offsets; the B3 literal pool is the
    verified five-hit cluster at `0x992c..0x996c`.
18. `tools/ghidra/run_drvui_resident_probe.sh` maps that B3 region together
    with the two resident `drv_ui` segments and verifies the extracted
    `bfc09c00` syscall wrapper, its second trap entry at `bfc09c04`, plus the
    `bfc0a8bc`/`bfc0a8c8` state setters.
19. `resident_ui.py` carries those verified resident effects into the host
    model while aligning odd MIPS16 pointers and refusing unknown targets.
20. `scan_mips16_imports.py` parses the real B3 MIPS16 disassembly and reports
    indirect-call delay slots that select an import number. The Ghidra import
    probe also maps the resident `0xbfc09c05` wrapper and verifies the
    four-argument selector-15/17 call shape. The selector semantics remain
    unresolved on M30; public ordinal names live in the reference-only
    `ui_imports.py` module.
21. `tools/ghidra/run_drvui_b3_table_probe.sh` maps the B3 tail and the
    following data area, then dumps the pointer words consumed by the second
    B3 UI function. It verifies the two resident entries and three
    bank-qualified candidates without treating any candidate as a directly
    executable address.
22. `tools/ghidra/run_drvui_b3_candidate_probe.sh` maps the B04, B07, and B08
    candidate banks together with their post-bank literal areas. The three
    aligned candidate offsets decompile as coherent MIPS16 functions, which is
    stronger evidence than a numeric bank-offset match but still not a proof
    of the hardware dispatcher ABI.
23. `tools/ghidra/run_folder_nav_probe.sh` decompiles the real browser/music
    `next` and `previous` handlers and checks their shared one-step and redraw
    contract before the acceleration candidate is considered applicable.
24. `compare_firmware_versions.py` compares hashes and provider-pointer counts
    across a directory of extracted M30 release images. It excludes the known
    `drv_ui.drv` consumer by default, so repeated pointer literals in the
    container or consumer are not misclassified as provider modules.
25. `tools/ghidra/run_browser_layout_probe.sh` checks the browser's literal
    table for the exact list-buffer and adjacent `path_info` addresses. This
    keeps the 8x50-byte layout boundary executable as a regression rather than
    leaving the 64-byte overlap warning as prose only.
26. `folder_ui.py` keeps the selected directory item visible in the seven-row
    directory viewport (the eighth allocated slot is the title). It wraps at
    both list edges, matching the real handler boundary matrix, and records the
    source row index in the trace; it models folder navigation state, not the
    proprietary LCD or input timing. Its `move_event()` method consumes the
    same burst gears as `scroll_accel.py`, so accelerated up/down events and
    viewport movement can be tested together without implying a hardware
    input-rate measurement.
27. `FolderList.marquee_offset_at()` and `render_at()` advance the host frame
    on the requested 50 ms marquee tick. They use integer
    timestamps for reproducible screenshots/traces and leave the firmware's
    0x32-byte name-slot truncation unchanged.
28. `tools/sim/ui_imports.py` records a reference-only ordinal cross-check from
     the public UI operation table: selector 15 is `UI_SCROLLSTRING_EXT` and
     selector 17 is `UI_GET_DC_STATUS`. It does not assign those names to the
     M30 dispatcher or execute either unresolved import.

29. `folder_input.py` connects the proven application key-filter model to
    `FolderList`: stock screen-off navigation produces only the wake decision,
    while the patched five-key path reaches the same accelerated folder
    navigation used by the host view. It accepts normalized key events and
    does not infer the physical key-driver sampling values.

30. `ui_resources.py` loads the six-corpus-verified MCG node records and RU3
    group metadata as immutable emulator inputs. It keeps node IDs and group
    indices structural until an AP/resource lookup proves their UI meaning.

31. The decoder write boundary lives in `tools/sim/decoder_output.py`. Both
    an unconfigured callback and the community's known rejecting callback
    return `-1`; unknown callback pointers are fail-closed and no rejected
    payload is counted as PCM.

32. `tools/sim/sd_block_device.py` provides the evidence-backed 512-byte SD
    sector boundary with explicit card presence and bounds checks. It does not
    claim to emulate the controller's register polling or filesystem walker.
33. `driver_loader.py` can apply the verified `yqhx` segment payloads and BSS
    zeroing into sparse byte-addressable resident memory. It never invokes the
    image entry point or the unresolved fixed-ROM handler.
34. `usound_operation_table_probe.py` hash-checks the final eight words in the
    fourth `usound.drv` segment. `tools/sim/usound_driver.py` resolves a
    `table[op]` candidate and clears the MIPS16 tag, but leaves ROM dispatch
    unexecuted because the indexing rule is still inferred.

`tools/sim/ui_calls.py` carries the resulting safety policy into the host
model: B02 `+0x471` is `lazy-load-then-call`, B02 `+0x4e9` is
`never-direct-call`, and A19/B16 candidates remain `lazy-only` because their
providers are absent from the extracted M30 driver.

`LazyBankRuntime.dispatch()` exposes the same policy in every
`DispatchResult`, so callers do not have to infer safety from the status
string or execute an unresolved candidate accidentally.
`LazyBankRuntime.boundary_trace()` additionally models the evidence-based
`overlay-exception -> bank-load -> retry-entry` envelope. The fixed exception
handler that joins those steps is not present in the extracted image, so the
trace is deliberately a host boundary and stops at an unvalidated entry.

`test_real_drv_ui.py` repeats the critical B02/A19/B16 assertions against the
archived community `drv_ui.drv`, in addition to the synthetic unit fixtures.

The complete read-only validation suite is:

```sh
bash tools/emu/run_static_suite.sh
```

Use `--host-only` when historical firmware corpora or Ghidra/MIPS binutils are
unavailable. It runs only the three local unittest suites and does not require
the cross-version binary-probe inputs. The default mode also runs the real
driver, kernel, exception, and MIPS16 `next`, `previous`, and `redraw` scroll
probes.

The default mode checks for all six extracted corpora before running any
probe, and reports their names if one is missing. This keeps a missing
historical input from looking like a later probe regression.

For an independent observation of the recovered `break 0` exception, run:

```sh
bash tools/emu/run_qemu_exception_trace_probe.sh
```

This builds a passive QEMU 11.1.3 TCG plugin and runs it alongside the guest
CP0 probe. The plugin only records the exception discontinuity; it cannot read
or modify guest state. The expected result is a `from=0x800526aa` to
`to=0x80010180` transition followed by the guest's exception-code/EPC trace.

To regenerate the safe UI member tree from an extracted community firmware,
use `build_safe_ui_members.py`; it verifies that only `music.sty` and
`setting.sty` changed. It does not create a flashable HEX.

The upgrade screen can be previewed with the real `upgrade.sty` bitmaps and
the host-selected radio option:

```sh
PYTHONPATH=tools/emu python3 tools/emu/upgrade_ui.py \
  --style analysis/community/fw/upgrade.sty \
  --font analysis/community/fw/new_m.fnt \
  --selected 2 \
  --out /private/tmp/m30-upgrade-selected.ppm \
  --trace /private/tmp/m30-upgrade-selected.json
```

This is a resource-backed screen preview, not an updater implementation. The
two visible text values are host examples; `upgrade.ap` does not provide them
as literals in the recovered screen entry. The separate
`Confirm upgrade file: [%s]` string belongs to `setting.sty`, and its caller
and on-screen geometry have not been connected to this screen. File search,
validation, confirmation, and power-off transitions remain a separate
reverse-engineering boundary.

The verified part of that boundary can be recorded without inventing updater
behavior:

```sh
PYTHONPATH=tools/emu python3 - <<'PY'
import json
from upgrade_flow import UpgradeFlowTrace

flow = UpgradeFlowTrace()
flow.select(2)
flow.request_confirm()
flow.search_complete(filename="MECHEN_M30 HEX")
print(json.dumps(flow.trace(), indent=2))
PY
```

`upgrade_flow.py` is trace-only. It records the known selectors and preflight
outcomes, but never emits a flash, reboot, or successful-update event while
the fixed-ROM dispatcher remains unavailable.

The kernel-side constant boundary can be reproduced without a device with:

```sh
bash tools/ghidra/run_kernel_bank_boundary_probe.sh \
  analysis/community/fw/kernel.drv
```

Static references to the resident-table candidates can be checked with
`tools/ghidra/run_kernel_loader_refs_probe.sh`; the current extracted image
has zero direct references to all six targets, so their callers remain table
or external-dispatch mediated.

The external-provider scan can be widened from `.drv` members to the complete
extracted firmware tree:

```sh
PYTHONPATH=tools/emu python3 tools/emu/provider_scan.py \
  --all-files analysis/community/fw
```

Repeatable `--pointer 0x...` arguments allow the same tool to scan a custom
provenance set. The known consumer `drv_ui.drv` contains the A17/A19/B16
literal references by design, so a provider-only negative check excludes that
file:

```sh
PYTHONPATH=tools/emu python3 tools/emu/provider_scan.py \
  --all-files analysis/community/fw --exclude-name drv_ui.drv --require-zero
```

That check currently finds zero copies in the other extracted members. The
separate `--require-zero` call in the static suite checks the six resident
loader addresses and `0xbfc06d6c` across the complete tree.

For a corpus of extracted release directories, use the version comparison
tool. Each child directory must contain a `fw/` directory:

```sh
PYTHONPATH=tools/emu python3 tools/emu/compare_firmware_versions.py \
  /private/tmp/m30-version-compare --require-zero
```

The tool reports module SHA-256 values and provider-only pointer counts for
each version. See [`docs/findings-version-corpus.md`](../../docs/findings-version-corpus.md)
for the twelve-image result currently archived locally.

Run it with extracted firmware resources:

```sh
PYTHONPATH=tools/emu python3 tools/emu/m30_ui.py \
  --music-ap build/qemu-minimal-safe/work/verify/fw/music.ap \
  --music-sty build/qemu-minimal-safe/work/verify/fw/music.sty \
  --title 'LONG TITLE' --artist 'ARTIST' --album 'ALBUM' --file 'FILE' \
  --out /private/tmp/m30-now-playing.ppm \
  --trace /private/tmp/m30-now-playing-trace.json
```

This does not execute the MIPS bank. It is the host-side framebuffer and
trace target for the next QEMU step. A complete ATJ2127 boot still needs the
real bank loader, UI dispatcher, LCD, key, SD and decoder devices; those are
not silently replaced by this renderer.

## Static RU3 screen atlas

The extracted card firmware contains several screen resource members. Their
PicBox coordinates, direct RGB565 bitmap records, and TextBox rectangles can
be previewed without writing the device or mounting a writable image:

```sh
bash tools/emu/render_style_atlas.sh \
  /private/tmp/m30-card-inspect.sNixua/fw \
  /private/tmp/m30-style-atlas
```

This produces one PPM/PNG and JSON provenance trace per `.sty` member,
including `upgrade`, `mainmenu`, `common`, `music`, `playlist`, `udisk`, and
`usound`. If `new_m.fnt` is present beside the styles, text uses the shipped
font reader; otherwise it falls back to the host test font. The bitmap
placement is resource-backed and the AP-driven screen state is not inferred.
In particular, a frame selection such as play/pause or an upgrade progress
state remains an explicit preview input until the corresponding dispatcher
state is recovered.

The now-playing renderer follows the same rule. It draws the real `music.sty`
PicBox resources at their recorded coordinates, uses the real TextBox
viewports for metadata scrolling, and parses the real timebox/progressbar
records. The 8x12 digit resources (`18..27`) plus the real colon/slash
resources are used for the elapsed/track counters; the host supplies their
values until playback state is connected. Labels are limited to the literal
`TITLE`, `ARTIST`, `ALBUM`, and `TRACK` strings observed in `music.ap`.
Unresolved AP state still controls which optional icon/frame is selected.

The transport frame mapping is explicit from the bitmap atlas: `music.sty`
PicBox 4 uses play/pause/next/previous at frames 0..3, while PicBox 1 uses
shuffle-off/shuffle-on at frames 0..1. The observed header overlay uses
PicBox 1 frame 1 and PicBox 0 frame 2. Its speaker/storage/play/battery
silhouettes come from `common.sty` resources 3/18/2/27; the host does not
invent replacement artwork when `common.sty` is available.
PicBox 3 is a separate EQ indicator: frame 0 is blank and frame 1 contains
the shipped `EQ:` bitmap. It is not the bottom progress bar; that bar is the
independent `progressbar[0]` record. PicBoxes 5..8 are fixed album, artist,
title, and track glyphs, and their roles are included in the JSON trace.
The now-playing refresh path selects controls `0x1002..0x1008`; PicBoxes 0 and
1 are retained in the style atlas but are marked inactive in the now-playing
paint trace because the header is a separate overlay. PicBoxes 5..8 are
painted as the aligned row icons, with values starting at the real `x=190`
TextBox viewports. Only the transport control is transient; pass
`--show-controls` to render its `0x1004` branch explicitly.

## Folder/list visualizer

`folder_ui.py` renders the evidence-based browser/list layer separately from
the now-playing screen. It uses the observed `common.sty` geometry (`x=35`,
205 px by 16 px text viewport, 30 px rows) and applies the browser's 50-byte
UTF-16 name slot before drawing. This is important for the original symptom:
the marquee can move a name that was already truncated by the list buffer, but
it cannot restore discarded characters. The selected row is redrawn as a
solid selection background on every host frame, so a later QEMU shim can be
checked for the red-tail artefact independently of the proprietary font.

Example:

```sh
PYTHONPATH=. python3 tools/emu/folder_ui.py \
  --out /tmp/m30-folder.ppm \
  --trace /tmp/m30-folder.json \
  --selected 1 --offset 7 \
  Music "A very long folder name that is truncated"
```

The output is still a host visualization; it is not evidence that the M30
firmware boots under generic `qemu-system-mipsel`.

When `--trace` is supplied, the JSON also contains `observed_dispatch`: the
verified nine-call envelope from the Ghidra real-bank probe. Pointer payloads
are kept as semantic labels until their internal ownership is proven.
The same JSON includes a one-based `navigation` snapshot (`total`, `start`,
`end`, and `active`) so host UI tests can assert list state independently of
the framebuffer pixels.

Pass `--common-sty PATH` to load the resource-backed list colors and geometry
instead of the host defaults. The current card/UI-blue resource yields
selection RGB565 `0x22f6`; the archived community baseline yields the original
red `0xd000`. This affects only the host preview and never rewrites the
resource.

If `new_m.fnt` is beside `common.sty`, the preview loads it automatically and
uses the host display rasterizer's per-glyph advances for painting and
marquee timing. These describe the preview renderer, not recovered firmware
font metrics. The filesystem callback requests 25 UTF-16 units into a 50-byte
destination, but the ROM provider's encoding and long-name behavior are
unknown. The preview defaults to supplied provider text. To compare it with a
candidate 46-byte payload that reserves an assumed BOM and terminator, pass
`--scroll-source slot-assumption`; this is not proven device behavior.

The browser trace also compares that host width with raw `new_m.fnt` record
advances and the prior fixed 8-pixel cell model. These are candidate width
calculations only: the firmware call path that consumes the font record
advance is not yet connected to the browser's marquee threshold.

```sh
PYTHONPATH=. python3 tools/emu/folder_ui.py \
  --common-sty analysis/community/fw/common.sty \
  --selected 1 --offset 64 \
  --scroll-source provider-output \
  --out /private/tmp/m30-browser.ppm \
  --trace /private/tmp/m30-browser.json \
  Music "A very long folder name that should scroll continuously" Podcasts
```

`python3 tools/emu/compare_folder_nav_host.py` executes the 28-case real
browser/music p-code matrix and compares the recovered
`start/end/active/previous/status/update` tuple plus redraw/reset helper hits
against `folder_nav_state.py`. It also checks active-item and edge-wrap
behavior in the visual `FolderList` model. Each AP is imported once per run;
the matrix is therefore suitable as a focused regression probe without the
old one-process-per-case overhead.

## Preparing the real banks for QEMU

`prepare_qemu_banks.py` extracts every bank from `music.ap` and emits an
assembly include, a linker fragment, and a manifest. Each bank remains at the
exact load address recorded in the AP table, including the bank containing
`FUN_40d54f2c`. This is the input boundary for the next user-mode harness:
the manifest also records the source AP SHA-256, and probe preparation checks
that the source hash is unchanged before and after copying.

```sh
PYTHONPATH=tools/emu python3 tools/emu/prepare_qemu_banks.py \
  build/qemu-minimal-safe/work/verify/fw/music.ap /private/tmp/m30-music-banks
```

The generated files are intentionally separate from the firmware build. No
HEX is changed and no new code is flashed by this step.

When assembling the generated include, run the assembler from the generated
directory because the `.incbin` paths are relative to it:

```sh
cd /private/tmp/m30-music-banks
mipsel-linux-gnu-as -EL -mabi=32 -mips32 -o banks.o banks.s
```

The complete probe build/run is automated by
`tools/emu/run_qemu_probe.sh`:

```sh
CROSS=mipsel-linux-gnu- QEMU=qemu-mipsel-static \
  bash tools/emu/run_qemu_probe.sh \
  build/qemu-minimal-safe/work/verify/fw/music.ap /private/tmp/m30-qemu-probe
```

When user-mode QEMU is available, the script captures the complete renderer
return value and the event-mutated descriptor. The optional third argument
selects the real bank path:

Execution is bounded to eight seconds by default; `QEMU_TIMEOUT` overrides the
limit. If the guest does not exit in time, the Python runner terminates and
waits for its QEMU child before the temporary output is removed.

```sh
for mode in cancel next previous redraw; do
  CROSS=mipsel-linux-gnu- QEMU=qemu-mipsel-static \
    bash tools/emu/run_qemu_probe.sh \
    build/qemu-minimal-safe/work/verify/fw/music.ap \
    "/private/tmp/m30-qemu-$mode" "$mode"
done
```

`cancel` requires renderer status `0x8014`; the event modes require the same
active/previous/status/y tuples as the Ghidra probe. The script still produces
the ELF and exits with a clear not-executed status when QEMU is unavailable.

The B3 import-selector cross-check is read-only and compares MIPS binutils with
Ghidra's mapped instruction list:

```sh
bash tools/ghidra/run_drvui_import_probe.sh analysis/community/fw/drv_ui.drv
```

## Executing the real MIPS16 path in Ghidra

The isolated renderer can be executed without a full SoC model:

```sh
bash tools/ghidra/run_scroll_probe.sh \
  build/qemu-minimal-safe/work/verify/fw/music.ap
```

The script builds the raw stub section when needed, imports the AP with
`LoadAP.java`, initializes the recovered scroll descriptor, and runs
`FUN_40d54f2c` until its controlled return. It uses a temporary project and
does not alter the firmware or create a flashable image.

To exercise one real event transition directly:

```sh
bash tools/ghidra/run_scroll_probe.sh \
  build/qemu-minimal-safe/work/verify/fw/music.ap next
bash tools/ghidra/run_scroll_probe.sh \
  build/qemu-minimal-safe/work/verify/fw/music.ap previous
bash tools/ghidra/run_scroll_probe.sh \
  build/qemu-minimal-safe/work/verify/fw/music.ap redraw
```

The probe asserts the active/previous slot bytes and status after each event.

For a single-process persistence check, use the sequence probe:

```sh
CROSS=mipsel-linux-gnu- QEMU=qemu-mipsel-static \
  bash tools/emu/run_qemu_sequence_probe.sh \
  build/qemu-minimal-safe/work/verify/fw/music.ap \
  /private/tmp/m30-qemu-sequence
```

It executes `next, next, previous, redraw` against the same recovered state
and requires the final tuple `active=1, previous=2, status=1, y=50`.
QEMU is bounded to eight seconds by default; set `QEMU_TIMEOUT` to override
that limit. A timeout terminates and waits for the guest process before the
wrapper returns.

## Folder-list acceleration model

`scroll_accel.py` mirrors the existing `tools/scroll/accel.c` candidate in
the host emulator. It covers the burst gears, direction/pause reset, list
boundaries, and the mode-4/update-2 page-reload contract. The modeled input is
the shared folder-list next/previous callback, so it applies to up/down key
events as well as any other control routed through those callbacks. It is a
model only: it does not patch an AP or claim that the device's physical key
repeat/event rate has been measured.

The burst boundary is explicit in the model: events separated by 700 ms or
less remain in the same burst, while a 701 ms gap resets to one item. Thus a
physical up/down key repeat rate can be checked directly against this contract
without assuming that the control is a wheel.

`tools/ghidra/run_folder_nav_pcode_probe.sh` executes the real browser and
music `next`/`previous` handlers in Ghidra's MIPS16 p-code emulator. It
intercepts only the redraw helpers and asserts the recovered seven-row window
transition (`7 -> 8` and `7 -> 6`); it is not an LCD or full-boot emulator.
`tools/ghidra/run_folder_nav_matrix_probe.sh` extends this with real p-code
boundary cases for 1, 2, 7, 8, 9, and 10-item lists: single-item no-op,
small-list wrap, page-end redraw, top/bottom wrap, and an interior previous
step. It runs the same cases against both browser and music banks and checks
the complete state tuple, including redraw/update status. The matrix is still
a handler-level proof, not a complete device boot.
