# QEMU coverage for the M30 work

## What is emulated

The QEMU harness is prepared for QEMU's MIPS32 little-endian user-mode CPU
runner. It loads real M30 `.ap` banks into freestanding test harnesses and
replaces only the kernel/file-system/timer calls that each harness needs. On
this macOS host the equivalent executable check currently runs through
Ghidra's MIPS p-code emulator; neither path is a complete M30 boot.

The first Ghidra-guided UI layer is now in `tools/emu/`.  It consumes the real
`music.ap` bank table and verifies the audited shared marquee instruction at
`0x40d55078`, consumes the real `music.sty` textbox records, and renders a
320x240 host framebuffer plus a JSON UI-dispatch trace.  This gives us a
repeatable target for the eventual QEMU host shim without presenting a static
HTML mockup as firmware execution.  The renderer is deliberately labelled a
host emulator: it does not claim to emulate the proprietary LCD or UI
dispatcher.

The same host layer contains `tools/emu/folder_ui.py`. It renders browser/list
rows using the observed 50-byte destination slot and 25-unit filesystem
request (the provider's output encoding and truncation remain unknown), the
`common.sty` 205x16 text viewport at x=35, 30-pixel rows, and the host model's
50 ms candidate active-scroll period. Its JSON trace records supplied provider
text, optional assumed slot clipping, pixel offset, source row index, and
selected-row background restore. The optional 46-byte text payload assumes a
BOM and terminator; this is not verified device behavior. The eight allocated
list slots include one title slot, leaving seven visible directory rows.
Selection stays inside that viewport and wraps at the list edges, matching
the real browser/music handler boundary matrix. This makes the observed
"scrolls but is cut off" behavior and the folder up/down-navigation contract
testable. `FolderList.move_event()` consumes the same burst gears as the
existing acceleration model and applies the recovered wrapping callback. This
still does not claim that the proprietary
LCD/font/input-timing path is emulated or that physical repeat rates have been
measured.

`FolderList.render_at()` can render successive host frames at the model's
50 ms candidate tick. The public SDK reference uses an 80 ms callback, but the
timestamped host model is deterministic and useful for visual regression
traces; it is not evidence that the M30 stock timer has been recovered.

`tools/emu/prepare_qemu_banks.py` is the next bridge toward real bank
execution. It extracts all 87 `music.ap` banks and emits exact-address
assembly/linker inputs plus a manifest. The eventual user-mode harness will
add only the dispatcher/timer/resource shims that the traced function reaches;
it will not treat `qemu-system-mipsel -M malta` as an ATJ2127 machine.

The isolated probe is reproducible with `bash tools/emu/run_qemu_probe.sh`. On a
Linux host with MIPS binutils and `qemu-mipsel-static`, it links the real bank
code and runs the cancel-event path. On this macOS host the probe ELF links
successfully, but user-mode QEMU is not installed; `qemu-system-mipsel` cannot
substitute for it because it has no Linux user-mode runner or ATJ2127 machine.
The tracked system-QEMU probes also use bounded subprocess timeouts. The
isolated user-mode probe defaults to eight seconds (`QEMU_TIMEOUT` overrides
it), and terminates and waits for its QEMU child on timeout.

The build-only path was rechecked with Homebrew's available
`mipsel-linux-gnu-` binutils. It emits `qemu_probe.elf` and patches only the 13
audited literal-pool pointers; the command then stops with the expected
"QEMU user-mode not found" result. This confirms the real-bank ELF boundary
without presenting an unexecuted ELF as a QEMU runtime pass.

As a working executable fallback, `bash tools/ghidra/run_scroll_probe.sh`
executes the same real MIPS16 bank path in Ghidra 12.2's p-code emulator. The
scroll probe passed at the isolated boundary (359 steps); this raises
confidence in the call/return and state layout, but does not emulate the
ATJ2127 peripherals.

The folder-navigation bridge now has an equivalent p-code probe:
`tools/ghidra/run_folder_nav_pcode_probe.sh`. It executes the real
`browser.ap` and `music.ap` handlers at `0x487144a8` and `0x487146cc`, while
intercepting only their redraw/page-reset helpers. With a seven-row window and
ten-item list, the verified transitions are `next: active 7 -> 8` with a
page redraw and `previous: active 7 -> 6` without a redraw. This is stronger
than the earlier decompile-only contract, but it still does not emulate the
LCD, storage, input timing, or a complete M30 boot.

`tools/ghidra/run_folder_nav_matrix_probe.sh` adds fourteen boundary cases per
bank in one Ghidra import per bank. The real handler was verified for 1-, 2-,
7-, 8-, 9-, and 10-item lists,
including the single-item no-op, small-list wrapping, page-end redraw,
next-at-bottom wrap to the first item, and previous-at-top wrap to the last
page. Browser and music produced identical state tuples. The matrix does not
establish LCD rendering or physical key-repeat behavior.

The decompiled function signature and first shim boundary are now confirmed:
`FUN_40d54f2c(ushort event_or_style, int *scroll_state) -> short`. Its
literal-pool targets include the UI dispatcher `0xbfc16405`, backlight/status
helpers in `0x4845xxxx`, and the timer `0xbfc16bb1`. The host harness can now
fail closed on an unexpected AP pointer instead of calling an unverified
address.

For the driver-side marquee path, `tools/sim/ui_calls.py` now adds a second
fail-closed layer: the five observed B3 far pointers are labelled only when
their call shape matches the public UI signature (`fix_switch_fontlib`,
`fixf_get_string_length`, the two Arabic/Hebrew transforms, or
`put_string_line_sdimage`). This improves the host trace without pretending
that the missing ATJ2127 bank manager has been emulated.

The same model now records three additional B3 trailing-table entries. Their
aligned MIPS16 bodies were decompiled after mapping each nominal bank together
with its post-bank literal area: B04 `+0x751`, B07 `+0x694`, and B08 `+0x401`.
They are marked `lazy-load-then-call` only after that static validation; the
host never jumps to the raw far pointer or claims that the hardware dispatcher
has been recovered.

The `drv_ui.drv` static checks are grouped by input as well. Pointer, B2/B3,
resident-forwarder, bank-table, and MIPS16-import assertions now run through
`tools/ghidra/run_drvui_matrix_probe.sh` in one Ghidra import, while the former
single-purpose scripts remain available for focused investigation. The full
suite therefore no longer re-imports the same driver eight times.

## Latest results

| Area | Runner | Result |
| --- | --- | --- |
| Shuffle fix | real `mengine.ap` patch bank | PASS |
| Shuffle-all / play-all | real patched engine units | PASS |
| Scroll acceleration | real `browser.ap` bank, stock vs patched | PASS; 30,000 events, 0 failures |
| Folder-list acceleration contract | host model mirroring the real blob | PASS; 700 ms boundary modeled; physical event timing unmeasured |
| Folder-list handler contract | Ghidra decompile of browser/music next/previous | PASS; stock one-step and redraw calls present |
| Folder-list handler p-code execution | real browser/music MIPS16 handlers with helper intercepts | PASS; next/previous state assertions in both apps |
| Browser directory-name encoding boundary | `tools/ghidra/run_browser_directory_name_probe.sh` | PASS; real MIPS16 callback preserves a synthetic ASCII-only BOM-prefixed UTF-16 long name but routes a BOM-less one through its 8.3 formatter, changing the prefix. Three-row slot stride/output, list-window boundaries, and failed reads are checked. Provider output encoding and connection to the marquee renderer remain unverified. |
| Screen-off media keys | real common key-filter bank, five app contexts | PASS; regression-only, no firmware change retained |
| Library/index sorting | real stock/new index banks, synthetic 6,000-song library | PASS; byte-for-byte reproduction |
| Library scan capacity | `playlist.ap` fixed-array scan loop | PASS; stock 4,000-record stop modeled separately from file-format capacity |
| MUSIC.LIB file layout | documented library header, `tools/library/libmodel.py` sector writer, independent public hex comparison, and real M30 card image | PASS; header, section directories, padded record area, raw nodes, decoded `cluster_no`/`dir_entry` key, and bounded active-record lookup are modeled; primary location blob, record links, and exact duplicate-key tie-break remain unresolved |
| Filename natural-order contract | host model | PASS; deterministic host contract |
| Manual-index policy | host state model + static automatic-index guards | PASS; manual path and the one observed powered card-reinsertion rebuild case are separate; event detection and other triggers remain device-specific |
| Artwork audio-first contract | host state model | PASS |
| ASRC/EOF advance boundary | archived `music.ap` delta + host model | host contract present; mode-4 community path modeled, audible drain unverified |
| `mmm_bp` ring/fade boundary | six-corpus `mmm_bp.al` + host model | PASS; wrap-aware source-byte reads, 0x200 refill and fade arithmetic modeled; DSP/callback semantics unresolved |
| `mmm_bp` status-3 terminal path | `tools/ghidra/run_mmm_bp_status3_probe.sh` on stock/community | PASS; real status compare sees 3, writes context state 2 and two halfwords, returns before refill. The initial resident call and `FUN_bfc27520` are stubbed; status/frame are synthetic, so downstream drain and audible fade remain unknown. Both inputs are byte-identical. |
| Metadata reader boundary | archived `music.ap` delta + host model | host 0x200 split/flush, source-byte reads and trailing-skip arithmetic present; parser/callback ABI unresolved |
| Artwork fallback/cache | community `music.ap` decompile + host models | policy and `ALBUM.PIC` record modeled; JPEG decode, SD/CPU timing and UI render unverified |
| Screen-off key-filter/session path | real common key-filter bank + host folder session | PASS; stock wake and patched five-key navigation modeled |
| USB Audio descriptor/packet contract | six local `usound.drv` corpora + host model | PASS; UAC1 2ch/16-bit/48 kHz, 200-byte isochronous OUT maximum; frame-aligned shorter packets are accepted by the host stream model because the driver uses the received length |
| USB Audio descriptor patch | Hash-pinned host model and checked-in Ghidra MIPS16 p-code probe | PASS; six synthetic cases cover stock values, 24-bit/96 kHz, cleared interface flag, empty VID/PID/strings, serial lookup skipped, and empty serial. ROM copy/firmware-info services are stubbed and config values are synthetic; this does not verify config retrieval or hardware USB. Reproduce with `bash tools/ghidra/run_usound_descriptor_patch_probe.sh`. |
| USB Audio operation-table candidate | hash-checked fourth YQHX segment + host candidate resolver | PASS; final eight pointers and op-3 target match; `table[op]` remains an inference and candidate functions are never executed by this model |
| USB Audio EP0 request handler | Hash-pinned, checked-in Ghidra MIPS16 p-code harness plus broader scratchpad run | PASS for tracked frequency SET_CUR/GET_CUR, GET_MIN/MAX/RES, five mute/volume controls, and SET_INTERFACE alt 1 reset vs alt 0 preserve/ACK. The broader scratchpad run also observed descriptor GET and IN chunking, but does not have a checked-in replay. All runs use synthetic zeroed RAM-backed MMIO; real controller status transitions and USB timing remain unmodeled. Reproduce tracked coverage with `bash tools/ghidra/run_usound_interface_probe.sh`. |
| USB Audio EP0 real-code execution | `tools/emu/run_qemu_usound_ep0_probe.sh` | PASS; executes real `FUN_bfc303c4` MIPS16 handler under QEMU/Malta for valid SET_INTERFACE alt 1 and alt 0 requests. Checks setup-copy, ACK-helper bit write, four seeded state locations reset/preserved. Driver is relocated to `0x8fc30000` so direct MIPS16 calls retain their region; handler literals and ACK pointer are explicitly remapped. USB controller, external setup delivery, malformed requests, and semantic identification of state fields remain outside this probe. |
| USB Audio EP0-to-producer/DMA/completion handoff | `tools/emu/run_qemu_usound_ep0_stream_probe.sh` | PASS; alt 1 clears shared state, 74 real producer calls enqueue 14,208 seeded bytes in 192-byte packets, the full `0x3780` ring payload is checked, and threshold crossing executes `FUN_bfc31390`/`FUN_bfc312e0`; threshold-path ring address/control/size are checked before a separate setup subcase. Alt 0 preserves the queue and ready latch. A harness-injected call to real `FUN_bfc3125c` leaves `0x60` bytes after subtracting one `0x3720` block, keeps ready set, and changes a synthetic clock value from `0xa5` to `0xa4`. Control-bit OR behavior is covered in the separate completion matrix. All controller, MMIO, DMA, and completion signaling remain synthetic; USB/FIFO delivery, consumer, and DAC are not exercised. |
| USB Audio ring-consumer/stream boundary | Ghidra `usound.drv` + bounded host stream | PASS; 0x1000-byte wrapped byte reads, firmware's zero-on-wrap read-pointer reset, and 100-short-read reset; decoder/PCM ownership remains separate |
| Real `usound.drv` leaf execution | exact 0xbfc30000 segment + QEMU/MIPS16 JALX wrapper | PASS; `FUN_bfc30cac` byte copy and `FUN_bfc30ce8` 3-to-4 expansion execute in QEMU; globals/peripherals remain outside |
| Real `usound.drv` ring-consumer execution | exact segment + relocated literal-pool state + real MIPS16 `break 0` module call | PASS; `FUN_bfc31654` consumes linear and wrapped requests, invokes module `0x10` twice on wrap, and resets its read pointer to zero; fixed-ROM service/DAC side effect remains unresolved |
| USB consumer module-0x10 candidate copy ABI | `tools/emu/run_qemu_usound_chain_probe.sh` | PASS; real producer/consumer arguments are trapped; a synthetic exception shim constrained to `(a0=0x80065000,a1=ring source,a2=16,a3=0)` copies the source into a sentinel-filled destination, which is verified byte-for-byte. The tuple matches the independent `aud_dev` candidate copy shape, but the ROM service implementation and PCM/DAC meaning remain unverified. |
| Real `usound.drv` module stubs | exact segment + QEMU exception-vector shim | PASS across all six local driver corpora; the real `0xbfc326a9` module-`0x10` stub and `0xbfc326ad` module-`0x0f` stub both raise exception code 9, with their respective four arguments captured. The module-`0x0f` check injects a setup-shaped tuple directly into the stub; it does not execute `FUN_bfc322e4` or the fixed handler. The actual caller tuple is captured separately by the Ghidra p-code setup probe below. |
| Real `usound.drv` setup callsite | `tools/ghidra/run_usound_setup_probe.sh` | PASS; executes `FUN_bfc322e4` in MIPS16 p-code with RAM-backed synthetic MMIO, intercepting module stubs and returning `v0=0`. Captures 10 module-`0x0f` calls and one module-`0x10` call, including actual `(a0=0x14,a1=0xbfc3125d,a2=0,a3=0x14)` and `(a0=0x0a,a1=0xbfc31115,a2=0,a3=0x14)` tuples. The probe retains the latter pointer and, under a synthetic event bit and manual dispatch, executes its real callback to producer entry `0xbfc30dc4`. Fixed-ROM service effects remain stubbed. |
| Passive QEMU exception observation | QEMU 11.1.3 TCG plugin + callback probe | PASS; independent observer reports `from=0x800526aa -> to=0x80010180`; guest CP0 trace remains authoritative for Cause/EPC/Status |
| Extracted M30 kernel exception vector on Malta | `tools/emu/run_malta_exception_vector_probe.py` | PASS; this isolated harness executes the hash-pinned real `kernel.drv` vector at `0xbfc08180`, dispatches ExcCode 9 through a preseeded slot-9 value recovered from the real `ker_init` constructor, and verifies saved EPC/RA plus ExcCode 9 in the saved Cause word. Malta BIOS byte-swapping and the vector's low-address TLB mapping are modeled explicitly. Constructor execution is covered separately by the combined probe below; the fixed-ROM handler body, device boot sequence, and ATJ2127 peripherals are not executed here. |
| Real M30 exception vector + synthetic slot-9 bridge + extracted xsys entry | `tools/emu/run_malta_exception_vector_probe.py <kernel.drv> --xsys <ker_xsys.bin>` | PASS on QEMU 24Kf/34Kf; raises a real `break 0`, executes hash-pinned `kernel.drv` vector `0xbfc08180`, dispatches through slot 9 containing the constructor-observed pointer `0xbfc06f18`, then enters real `ker_xsys` code at `0xbfc34074` through an explicitly synthetic direct-jump bridge. Checks the vector EPC/RA/Cause words, appended xsys EPC/RA words, and frame guards. The slot value is seeded; the ROM bridge and `0xbfc06d6c` checkpoint are harness code, so the real handler body, handler-to-entry edge, constructor call, device boot, and peripherals remain unmodeled.\n| Extracted M30 exception-table constructor + vector on Malta | `tools/emu/run_malta_constructor_vector_probe.py` | PASS on QEMU `24Kf` and `34Kf`; executes hash-pinned `ker_init.bin` constructor `0xbfc32bc8` and its actual MIPS16 setter, checks table slots 0..13 and untouched sentinels at 14/31, then executes the real `kernel.drv` vector and verifies saved EPC/RA/Cause for ExcCode 9. Two audited pointer bases are redirected to one RAM table. The two words at `0xbfc32c5c` and its delay slot are replaced with a jump to the post-constructor checker, matching the p-code probe's stop boundary. The unavailable fixed-ROM handler is a stub; device boot invocation, peripherals, and hardware fidelity are not established. |
| Extracted M30 exception stack and `ker_xsys` entry on Malta | `tools/emu/run_malta_xsys_stack_probe.py <ker_xsys.bin>` | PASS on QEMU `24Kf` and `34Kf`; executes real hash-pinned MIPS32 selector `0xbfc340a4` against table address `0xbfc19974`, with six audited `lui` operands redirected to RAM and a return-only stub at `0xbfc05f90`. Index 0 covers below/equal alternate and above direct; index 1 uses table[0]=`0x12000`, table[1]=`0x10101`, SP mask `0x11000` so wrong-index and unaligned-word reads would take the alternate path. It also directly calls real entry `0xbfc34074` with seeded CP0 EPC, verifies stores to `0x13dd8/0x13ddc` through a TLB-mapped RAM frame, and returns through a stub at `0xbfc06d6c`. Table/global state, EPC, and both ROM boundaries are synthetic; no natural exception/caller/fixed-ROM handler or ATJ2127 peripherals are modeled. Result bytes poll Malta 16550 THRE. |
| NORMBREC candidate startup path | `tools/ghidra/run_normboot_path_probe.sh` | PASS in Ghidra p-code with the hash-pinned NORMBREC member mapped at candidate base `0xbfc08000`; executes its real MIPS32 jump, stack setup, and `jalx` into MIPS16e. `RELP=1` is seeded; AFI load address, reset context, bootloader invocation, and stage order are not modeled. |
| NORMBREC MIPS16e indirect-call path | `tools/ghidra/run_normboot_service_path_probe.sh <NORMBREC.BIN>` | PASS in three Ghidra p-code fixtures (58/78/61 executed MIPS instructions, excluding synthetic callback interceptions): small zero-return, large zero-return, and small nonzero-return. The large case verifies the second `0xbfc002a8` call and advanced `0xbfc11800` argument; the nonzero case verifies `0xbfc002a0` is called with the first callback tuple before later calls continue. All four argument registers are asserted. Callback semantics, real ROM slot contents, runtime mapping, and boot behavior remain unknown. |
| UDisk AP reference to `0xbfc002a0` | `tools/ghidra/run_normboot_udisk_pointer_probe.sh <community-udisk.ap> <stock-udisk.ap>` and `tools/ghidra/run_udisk_fixed_callback_probe.sh <stock-udisk.ap>` | PASS; both full input hashes are pinned and the `0xfcc..0x1050` code/literal slice is byte-compared. P-code executes the real stock caller: two resident calls are intercepted with synthetic zero returns; seeded ROM slot `0xbfc002a0=0x00704001` is dereferenced, stored at `0x9fc1564c`, then called; synthetic MMIO `0xc0020000: 0xa5a5005f -> 0xa5a5006a`. The callback body and actual ROM slot contents remain unknown. |
| NORBREC MIPS16 indirect callback bridge | `tools/ghidra/RunNorBootCallbackBridgeProbe.java` via `tools/ghidra/run_norboot_probe.sh` | PASS; executes real candidate bridge `0xbfc32058` with synthetic external state and pointer slot `0xbfc02608`. At `a2=0x28` it calls the seeded MIPS16 target with `(0x5400,0x9fc13000,0x5000)` and returns to `0xbfc32084` via tagged RA `0xbfc32085`; at `a2=0x29` it returns without calling. The callback body, actual fixed-ROM pointer/data, parent caller, and boot-time invocation are not modeled. |
| NORFWSC ADFU result-descriptor tail | `tools/ghidra/run_nor_fwsc_probe.sh` | PASS; executes the real MIPS32 conditional return tail at candidate `0xbfc1e2a8` with a seeded nonzero helper result and stack frame. Returns descriptor `0x9fc24cfc` containing data pointer `0x9fc24ca4` and size `0x58`; helper bodies, alternate branch, AFI entry semantics, and M30 invocation remain unverified. |
| USB mass-storage request boundary | Ghidra `uhost.drv` command builder + host model | PASS; request fields and exact sector-byte transfer through the SD device modeled; CBW/CSW framing, status meaning and controller timing unresolved |
| usound MMIO word-port boundary | Ghidra literal pool + generic host model | PASS; modes 1/2/4 and gate are modeled without USB/PCM claims |
| Real `usound.drv` word-port execution | exact segment + relocated bfc3194c/bfc31950/bfc31954 pool | PASS; `FUN_bfc318cc` executes mode 1/2/4 and gate behavior in QEMU; the guest RAM ports are not physical MMIO |
| usound byte-format boundary | Ghidra copy/3-to-4 helpers + host model | PASS; direct copy and complete 3-byte to 4-byte expansion |
| usound software-ring producer | Ghidra `FUN_bfc30dc4` + host model | PASS; 0x6e40 wrap, overrun rule, 0x1b90/0x3720 thresholds, and ready-latch rearm after empty DMA completion |
| Real `usound.drv` producer execution | exact segment + relocated literal-pool state + real MIPS16 leaves | PASS; `FUN_bfc30dc4` executes linear, wrapped, and fixed-point 3-to-4 producer paths in QEMU; callbacks and MMIO are shimmed |
| Real usound event callback → producer → DMA threshold | `tools/emu/run_qemu_usound_event_dma_probe.sh` | PASS; 74 event callbacks enter real `FUN_bfc31114`, call real `FUN_bfc30dc4`, and reach `FUN_bfc31390`/`FUN_bfc312e0`; producer DMA ring and staging/consumer address remain separate. RAM-backed event/MMIO, seeded input, ROM dispatch, physical DMA, and consumer execution remain outside |
| Candidate usound producer/consumer composition | `tools/emu/run_qemu_usound_composition_probe.sh` | SYNTHETIC MISMATCH REPRODUCED (probe passes); real callback/producer/word-port code writes complete 192-byte A/B packets to the DMA ring. The harness then manually calls the real consumer, which reads the second 192-byte source as the initial 0x11 fill; only the first byte at producer staging base is verified as B. No firmware call edge between these paths is established. Module-0x10 destination effect, fixed-ROM dispatch, physical FIFO/DMA, and audible output remain unmodeled |
| Real `usound.drv` DMA completion policy | `FUN_bfc3125c` p-code with boundary matrix | PASS; subtracts `0x3720`; empty path and clock-control thresholds checked; physical MMIO effects are not modeled |
| usound ring-to-DMA register contract | static MIPS16 `FUN_bfc30dc4`/`FUN_bfc312e0` trace + host value model | PASS; DMA address is the `0x9fc24000` ring base; mode-specific control/size writes modeled, hardware size units unresolved |
| Real `usound.drv` DMA initialization and completion | `FUN_bfc312e0` + `FUN_bfc3125c` in QEMU with RAM-backed MMIO | PASS; both format modes and six completion boundaries; the internal stop helper is stubbed, so physical DMA/clock behavior remains unverified |
| Real `usound.drv` producer→consumer chain | exact segment + shared guest ring + real module-call exception shim | PASS across six corpora; producer publishes 16 bytes, `FUN_bfc31654` consumes 16, and module `0x10` traps once; PCM/DAC ownership remains unresolved |
| Real `drv_lcd.drv` window/orientation execution | exact resident segment + relocated command/data pool + MIPS16 callback shims | PASS; `FUN_bfc0a378` emits the verified `0x2a/0x2b/0x2c` path and `FUN_bfc0a438` emits orientation `2 -> 0x48`; physical panel timing remains unresolved |
| Real `drv_dac.drv` byte-transfer execution | exact resident segment + translated MIPS16 helper-call alias + synthetic RAM GPIO context | PASS across six corpora; real 8-bit transmit, two-byte public send, and all-high/all-low byte sampling execute. Ghidra also verifies an I2C-shaped START/STOP, MSB-first, active-low ACK/NACK sequence; physical pin identity/electrical semantics, codec register map, and audio output remain unresolved |
| `actions_flash` ATJ2127 `read_mem2` copy helper | source-transcribed 32-byte MIPS16 payload + QEMU/Malta synthetic RAM | PASS; real payload code copies the full `0x1d8`-byte maximum chunk byte-for-byte and changes argument 0 to `scratch+8`. The embedded pointer is relocated to the QEMU scratch address. This does not emulate ADFU USB commands, prove ROM mapping, or establish M30 payload compatibility |
| LCD-backed now-playing preview | real `music.ap`/`music.sty` host renderer + recovered LCD sink | PASS; visible 320x240 RGB565 round-trip with `0x36,0x2a,0x2b,0x2c` trace; hybrid preview, not full ATJ2127 boot |
| Combined visible preview session | `m30_preview_session.py` + LCD sink + usound module-call/stream boundaries | PASS; one JSON trace joins 76,800 LCD pixels, UI calls, module ID/args, and optional 200-byte stream framing; fixed-ROM/audio effects remain explicitly unresolved |
| Kernel slot-`0x0f` preview boundary | `FirmwareBreak0Boundary` + hash-pinned `kernel.drv` | PASS; SHM op `0x47/0x48/0x49` uses the p-code-verified pointer registry, all 29 ROM entries return an explicitly labeled synthetic zero, and other bank/resident entries expose their real table target without executing it. Op `0x4a` records a caller-supplied descriptor but does not create or run a task. The ROM dispatcher-to-table link remains INFERENCE. |
| Combined EQ/DAE preview session | `FirmwareEqAudioSession` + `M30PreviewSession` | PASS; Custom edits feed the measured builder/SHM bytes, music start replays the stored selector, and play reports the `mmm_mp` DSP filename plus bounded `aud_dev` `cfg+0x3a/+0x28/+0x40` state at 48 kHz and above. Busy-entry, speaker-compensation and nonzero adjustment paths fail closed; ROM, DSP code and audible output are not executed. |
| Ghidra-guided now-playing framebuffer | real `music.ap`/`music.sty` inputs + host renderer | PASS; bank/call-site/geometry checks |
| LCD address-window transfer | `drv_lcd.drv` commands `0x36/0x2a/0x2b/0x2c` + host framebuffer sink | PASS; recovered raw orientation values and bounded RGB565 window writes modeled; orientation semantics/controller timing unresolved |
| Real MIPS16 renderer smoke probe | exact `music.ap` banks + 13 verified pointer stubs | PASS in Ghidra p-code; 359 steps; QEMU ELF also links |
| Real MIPS16 event transitions | `FUN_40d54d80`, exact bank + key/UI stubs | PASS; next 94 steps, previous 90, redraw 121 |
| MCG/RU3 resource metadata | six-corpus byte-identical resource members | PASS; immutable node/group catalog, localized names unresolved |
| Decoder write callback | community `mengine.ap` callback boundary | PASS; unconfigured/known callback returns `-1`, unknown pointer fail-closed |
| EQ builder/shared-memory boundary | Ghidra MIPS16 p-code on stock and community `mengine.ap` | PASS; 11 builder cases byte-verified with matching cross-version digest; SHM payload reaches module `0x0c` op `0x12`, while ROM/DSP interpretation remains unresolved |
| EQ producer→candidate `aud_dev` consumer | `tools/ghidra/run_eq_auddev_chain_probe.sh` | PASS; actual builder band records for Off, Hip-hop, and default Custom are fed into candidate bank-5 MIPS16 code. Nine cases verify 16 records and gain-adjustment arithmetic using preserved `0xaa` harness sentinels plus synthetic neutral/two-range tails, under a candidate memcpy shim. Runtime tail values, ROM dispatch/bank mapping, DSP use, and audible effect remain unverified. |
| FAT32/exFAT extension boundary | six-corpus resident driver table | PASS; shared 31-slot vocabulary and explicit-slot host filter; caller mask/walker unresolved |
| Library extension masks | `playlist.ap` raw mask words at `0x2a20`/`0x2a28` plus resident matcher loop | PASS; MUSIC `0x7e190ea0` (14 bits), M3U `0x00080400` (2 bits); driver slot order is bit 31→slot 0 through bit 1→slot 30 |
| Library tag-reader selector | `playlist.ap` `FUN_40094f2c` mapping | PASS; types 0–7 plus type-8 fallback; reader ABI/metadata parsing unresolved |
| Library fallback record | `playlist.ap` type-8/tag-failure path | PASS; 8.3 title formatting, fixed fallback fields, and bounded track selector |
| MUSIC.LIB record codec | documented 256-byte scan record layout plus `index.c` field accesses | PASS; text/flags/track fields and the `cluster_no`/`dir_entry` key are decoded, while the primary location blob and record links remain opaque |
| Library directory walker | stock `playlist.ap`/playall walker contract | PASS; DFS order, depth 8, `RECORD` skip, target count; filesystem callbacks unresolved |
| SD sector/capacity boundary | `cards.drv` 512-byte transfer and capacity path | PASS; host sector device with card presence, bounds checks and explicit `sector_count * 512` capacity |
| Resident `yqhx` header/materialization | four header segment descriptors + host sparse memory | PASS; all four descriptor slots, nonempty segments and BSS are applied without entry execution; 28 local `.drv` files parse; real resident loader and fixed ROM handoff unresolved |

The latest QEMU library diagnostic was deliberately run as an ephemeral
diagnostic build with the experimental whole-card library enabled. It was not
used as the target firmware and produced no persistent 24K firmware artifact.

## What QEMU cannot boot yet

Homebrew QEMU currently exposes only the generic `malta` and `none` MIPS
machines. The M30 firmware expects the Actions ATJ2127 SoC, its bank-loader,
LCD controller, key controller, SD/FAT driver, DAC and codec/DSP devices. No
upstream machine model provides those peripherals. Passing the encrypted M30
HEX to `qemu-system-mipsel` would therefore not be a valid device test.

The following remain outside direct QEMU coverage:

- complete boot and menu rendering;
- physical SD-card insertion/USB-disconnect/index trigger;
- LCD bitmap output;
- decoder/DSP/ASRC audio tail and audible fade-out behavior;
- EQ coefficient loading and line-out analog routing;
- true gapless PCM handoff.

For those areas, the project keeps separate host contracts and byte/checksum
guards. They must not be reported as hardware-equivalent QEMU passes.

## Re-running the real-bank suite

Inside a Linux environment with the MIPS cross compiler, QEMU user-mode and
Rockbox `atjboottool` available:

```sh
ATJBOOTTOOL=/path/to/atjboottool \
CROSS=mips64-linux-gnu- \
QEMU=qemu-mipsel-static \
PYTHON=python3 \
./build.sh --test MECHEN_M30.HEX build/qemu-run
```

The optional library diagnostic is separate and should not be used for the
minimal-safe release:

```sh
./build.sh --test --library MECHEN_M30.HEX /tmp/m30-library-diagnostic
```

## ATJ2127 peripheral model

The repository now also contains a small register-level model in
`tools/sim/atj2127.py`. It is based on the public ATJ2127 reverse-engineering
notes from [actions_flash](https://github.com/kilicdev/actions_flash): UART
FIFO access, the Bluetooth power GPIO, the channel-1 memory-to-UART DMA path,
and the ADFU recovery marker/watchdog sequence are modeled and tested.

This is an intermediate step toward a full machine model. It lets harnesses
exercise firmware-facing peripheral contracts without pretending that the
M30's LCD, SD controller, decoder/DSP, or overlay loader are already emulated.
The actual `qemu-system-mipsel` binary still has no ATJ2127 machine target; a
full firmware boot remains future work.

The closest public reference is [PD196_ATJ2127](https://github.com/Suber/PD196_ATJ2127),
which includes the original-style browser/common UI sources and an old Win32
`case_simulator`. Its listbox driver confirms that the active directory item
uses a measured text rectangle and an 80 ms refresh callback. For the M30
resources, the extracted `common.sty` gives the directory text rectangle as
`x=35, width=205, height=16`, with the folder icon beginning at `x=5`.
Those values are represented in `tools/sim/listbox.py` and covered by tests.
The M30 callback passes a 50-byte destination slot and requests 25 UTF-16 units
from the filesystem helper. The ROM helper's output encoding, terminator, and
long-name behavior are unknown. The preview's optional 46-byte text capacity
is only a BOM-and-terminator assumption, so it does not establish that names
are truncated before reaching the marquee.

The application-level key filter is now represented by
`tools/sim/key_filter.py`. It is intentionally below the unresolved physical
key-driver/MMIO boundary: normalized events are delivered while the screen is
on, the `WAKE_ONLY` screen-off policy turns non-power events into wake message
`0x106`, and the remembered wake key's long-press/release sequence is
discarded. The changelog-backed `MEDIA_KEYS_PASS` policy routes only
play/pause, previous, next, volume up, and volume down through the normal
delivery path while the screen is off. This is host-emulator behavior with
deterministic tests, not a claim that physical button sampling or repeat timing
has been recovered.

`tools/sim/usb_audio_stream.py` now composes the observed USB Audio
descriptor with the 4 KiB ring-consumer boundary. The descriptor's 200-byte
field is treated as the packet maximum; frame-aligned payloads up to 200 bytes
are accepted, and oversized or partial-frame payloads are rejected. The host
model treats an empty payload as zero frames, but the firmware's zero-length
branch clears its 4 KiB staging buffer; equivalence of those behaviors is not
established. The driver reads the received length from USB MMIO and passes it
into the FIFO transfer path instead of requiring exactly 200 bytes. The host
stream preserves accepted packet bytes across the ring wrap and rejects
over-capacity writes. This is a host stream contract; it does not promote the
unresolved `usound` producer callback into a codec, DAC, or hardware-DMA
implementation.

The recovered packet/ring boundary also has a real QEMU system-CPU smoke probe:
`bash tools/emu/run_qemu_usb_audio_probe.sh`. It links freestanding little-
endian MIPS32 code and runs it on QEMU's Malta machine. The guest queues 32
synthetic 200-byte packets, consumes them in two batches, crosses the 4 KiB
ring wrap, and verifies every byte before reporting
`USB AUDIO QEMU BOUNDARY PASS`. Malta's FPGA UART is used only for the result
line. This is stronger than a host-only test for CPU, guest memory, and ring
arithmetic, but it is not a M30 boot: QEMU still does not model the ATJ2127
USB controller, VBUS/device negotiation, the `usound.drv` callback, codec/DAC
routing, or the physical headphone/line output.

`bash tools/emu/run_qemu_usound_leaf_probe.sh` goes one step closer to the
firmware: it extracts the exact 0x26b0-byte resident segment from
`usound.drv`, maps it at a fixed guest address, and enters the real MIPS16
functions `FUN_bfc30cac` and `FUN_bfc30ce8` through `JALX`. The wrapper checks
the copy and `[0,b0,b1,b2]` expansion outputs byte-for-byte. This proves real
driver instructions and their leaf calling convention under QEMU, but it
does not prove the driver entry, its global state, USB controller interrupts,
the callback at `0xbfc326a9`, or DAC/codec output.

`bash tools/emu/run_qemu_usound_ring_probe.sh` now executes the real
`FUN_bfc31654` consumer with its six recovered literal-pool state words
relocated into guest RAM and the actual `0xbfc326a9` callback restored. Two
calls pass: a linear 16-byte read and a wrapped 32-byte read that invokes the
real callback twice. The QEMU result exposed an important exact behavior:
after the read pointer crosses `0x1000`, the firmware stores zero rather than
retaining the remainder. `tools/sim/usb_ring.py` and its tests now follow that
observed behavior. The exception handler records the callback envelope only;
the kernel service, PCM ownership, and DAC output remain outside coverage.

`bash tools/emu/run_qemu_usound_port_probe.sh` executes the real
`FUN_bfc318cc` helper after relocating only its recovered gate, data-port, and
alternate-port literal words. QEMU verifies mode 2 reads, mode 1 writes, mode
4 alternate reads, and the gate byte that suppresses mode-2 reads. These are
guest-RAM stand-ins for the original MMIO addresses; no claim is made about
the physical USB controller, FIFO timing, or codec/DAC routing.

`bash tools/emu/run_qemu_usound_callback_probe.sh` executes the real odd
callback target `0xbfc326a9` (aligned MIPS16 entry `0xbfc326a8`) and the adjacent
module-`0x0f` stub at `0xbfc326ad`. The guest redirects CP0 `EBase` to a RAM
exception vector, catches each `break 0`, records CP0 `Cause`, `EPC`,
`BadVAddr`, and `Status`, and returns through the interrupted caller
continuation. Across all six local `usound.drv` corpora, QEMU observes
exception code 9 for both module IDs. For module `0x0f`, this direct-stub probe
captures a harness-supplied tuple; it does not run `FUN_bfc322e4` or the fixed
handler. The separate Ghidra p-code setup probe executes `FUN_bfc322e4` and
captures the actual `(a0=0x14,a1=0xbfc3125d,a2=0,a3=0x14)` tuple with synthetic
MMIO and module returns. Together these checks verify the trap envelope and
the driver's callsite dataflow, not the module service effect or eventual
PCM/DAC side effect. For the final module
`0x0f` call, QEMU reports `EPC=0x800526af`; masking the MIPS16 tag gives
`0x800526ae`, the address of its `break 0` instruction. This is not evidence
that the missing fixed-ROM handler is present in the extracted image.

Reproduce the setup callsite execution with:

```sh
bash tools/ghidra/run_usound_setup_probe.sh analysis/community/fw/usound.drv
```

It intercepts the module `0x0f` and `0x10` entry stubs before executing their
`break 0` instructions and returns zero. The register writes and callsite
arguments come from the real driver code; service behavior is not modeled.

`bash tools/emu/run_qemu_usound_producer_probe.sh` now executes the real
`FUN_bfc30dc4` producer. Its QEMU guest supplies only the recovered state
words, a no-op poll/fill/port callback, and the exact MIPS16 copy helpers. The
probe passes a linear write, a write split at the `0x6e40` ring end, and the
format-1 fixed-point `0xaaaaaaab` size path. This verifies the producer's
control flow and state arithmetic, but not the USB polling callback, hardware
FIFO, interrupt timing, or DAC/codec output. The same probe executes the real
`FUN_bfc312e0` setup in both format modes, checking channel control, raw size,
ring address, and both common control writes. The producer and setup share the
same relocated format flag, although each probe case sets it independently; the
producer-to-DMA format handoff is not tested end to end.

It also executes `FUN_bfc3125c` with post-subtraction counts `0`, `1`, `4409`,
`4410`, `9702`, and `9703`. The MIPS16 direct call to `FUN_bfc314e0` is adjusted
to the relocated guest address; that helper is replaced with a MIPS16 counter-and-return
stub so the probe verifies the empty branch calls it and isolates completion-state
writes. The helper shutdown callback, physical clocking, DMA timing, and audio
output remain untested.

`bash tools/emu/run_qemu_usound_chain_probe.sh` then runs the real producer and
`FUN_bfc31654` consumer in one guest, with separate recovered state descriptors.
The two distinct firmware ring literals (`0x9fc24000`/`0x6e40` producer ring
and `0x9fc34000`/`0x1000` consumer ring) are deliberately relocated to the same
guest base `0x80061000`. The probe seeds the producer's scratch with bytes
`0x10..0x1f`, checks the real producer copy helper writes them into the ring,
and checks that the consumer's module-`0x10` callback receives the same source
address and bytes. The module-`0x10` exception shim checks the observed
destination/length tuple, applies the candidate memcpy behavior, and verifies
the destination bytes. The nine local `usound.drv` artifacts have an identical
SHA-256; the representative community binary passes. This is synthetic
aliased-ring and candidate-ABI coverage, not proof that firmware naturally
connects these rings or that the stock ROM service, PCM owner, or DAC consumes
the same data on hardware.

`bash tools/emu/run_qemu_lcd_probe.sh` executes the real LCD resident helpers
`FUN_bfc0a378` and `FUN_bfc0a438`. The guest relocates only the driver's
command/data literal words and replaces setup/transfer callbacks with no-op
MIPS16 shims. Across all six local LCD corpora, a window `(x=2,y=3,w=4,h=5)`
reaches the final `0x2c` memory-write command with row-end byte `7`, and
orientation `2` reaches `0x36/0x48` with mode state `2`. This validates the
framebuffer command boundary, not LCD electrical behavior, controller timing,
or pixel visibility on the device.

`tools/emu/lcd_preview.py` composes that boundary with the real-resource
now-playing renderer:

```sh
python3 tools/emu/lcd_preview.py \
  --music-ap build/qemu-minimal-safe/work/verify/fw/music.ap \
  --music-sty build/qemu-minimal-safe/work/verify/fw/music.sty \
  --out /private/tmp/m30-lcd-preview.ppm \
  --trace /private/tmp/m30-lcd-preview.json
```

The resulting PPM is a user-visible 320x240 preview and the trace records the
four recovered LCD commands and all 76,800 pixels. It is intentionally called
a hybrid preview: the actual UI resource renderer is host-side until a
MIPS-user-mode run of the bank path and an ATJ2127 LCD device model are
available.

For one command that first executes the real LCD QEMU probe and then emits the
visible preview, use:

```sh
bash tools/emu/run_lcd_preview.sh
```

To run the LCD and callback probes first and then emit a single combined
visible/session artifact, use:

```sh
bash tools/emu/run_m30_preview_session.sh
```

This writes `/private/tmp/m30-preview-session.ppm` and
`/private/tmp/m30-preview-session.json` by default. The session is deliberately
hybrid: it joins proven boundaries but does not invent the absent ROM service,
codec, DMA, or audible playback semantics.

For a repeatable CPU/RAM-only regression pass over the recovered LCD, DAC,
USB Audio, producer/consumer, module-call, and exception boundaries, use:

```sh
bash tools/emu/run_qemu_boundary_suite.sh
```

The suite is intentionally separate from the static/Ghidra suite: it requires
`qemu-system-mipsel` and MIPS binutils, and every probe uses explicit synthetic
RAM or exception shims. A green result proves the recovered boundary and
argument/state contract only; it does not prove ATJ2127 hardware behavior or
USB-DAC audible output.

The boundary suite was run against all six complete local firmware
directories with `bash tools/emu/run_qemu_boundary_suite.sh --all-corpora`.
Every corpus produced the same LCD/DAC boundary results, 16-byte
producer-to-consumer transfer, and `module=0x10` exception envelope
(`Cause=9`, `EPC=0x800526ab`, four preserved arguments). The independent TCG
observer also reported the same transition to `0x80010180` in each run. This
cross-version agreement covers these extracted driver paths only.
