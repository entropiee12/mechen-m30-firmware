<!-- Statik analiz bulgusu (cihazda test edilmedi). Alt ajan çıktısı; ana oturum mengine.ap EQ tablosu/frekanslar/sıfır alanı, setting.ap tablosu/sıfır alanı, config.bin öğe 0x116 baytları ve config.ap Custom-buffer başlangıç/kapanış yolunu ayrıca doğruladı. VERIFIED/V = dump veya baytta okundu, HYPOTHESIS/H = çıkarım. Kaynak dosya: m30-work/findings/eq_usound.md. -->

# EQ + usound findings (read-only; V=VERIFIED, H=HYPOTHESIS)

> **UI correction (2026-11-24):** the earlier notes below called the
> `music.ap` row `0x3301` a Custom EQ row. String-table decoding disproved
> that: `music.sty` label `0x69` is `Omezení hlas.` (volume limit), and popup
> string `0x54` is its confirmation text. `FUN_40414c00` and its type-3 popup
> trace are therefore not evidence about EQ Custom selection or editing. The
> EQ labels are in `setting.sty`. The seven-band selector and Custom editor
> were subsequently found in `mainmenu.ap` (`FUN_40154cec` and
> `FUN_40154f44`). The mainmenu state table reaches both, but the root menu
> item that enters the EQ state and the relationship to the labelled
> `setting.ap` list remain unknown. See the
> [UI route and evidence limits](findings-eq-selector-writer.md).

## Selector-7 producer-to-builder p-code chain

`bash tools/ghidra/run_eq_custom_message_to_builder_probe.sh <hash-matching-music.ap> <matching-hash-mengine.ap>` runs linked, hash-pinned Ghidra p-code probes for matching stock V1.101.10 or community V1.292.00 inputs. The music-side trace supplies event/selector state and gain bytes to exercise the selector-7 producer, then captures its `0x303` payload. It does not establish that an EQ menu row on the device supplies those inputs. The second probe runs the real MEngine `0x303` dispatcher, handler, and builder instructions. A synthetic memcpy shim models the unavailable resident copy service; the builder output matches the supplied gains, including signed gain ×10 values.

The full linked run passes on both the stock V1.101.10 pair (`music.ap` SHA-256 `faa6b68a…d49193`, `mengine.ap` `1340bac3…e2eb49`) and the community V1.292.00 pair (`music.ap` `662e3796…1baac3`, `mengine.ap` `b569605e…e91be49`). The standalone producer probe also passes on stock, and the 12-case builder result digest is identical between stock and community (`f3a0583b…db8303`).

The linked replay is not a live inter-processor call. It executes the actual MEngine `FUN_60051278` state-setup instructions until the next internal call (`FUN_60051034`): under a nonzero synthetic return from the resident shared-memory service, those instructions store state base `0x9fc15cb0` into pointer cell `0x9fc15ec8` at instruction `0x600512f4`. Static Ghidra references show a computed-call site from `FUN_60010d00` at `0x60010d0e`, but the dispatcher selection that reaches it was not executed. The probe begins selector byte `0x9fc15cc9` at sentinel `0x55`. The real `0x303` handler calls the resident copy service with destination `*(0x9fc15ec8)+0x18 = 0x9fc15cc8` and length four. Payload byte `+1` therefore overlaps selector address `0x9fc15cc9`. The harness's synthetic memcpy shim changes the sentinel to `7`; the fixed-ROM copy service is unavailable, so that byte write is not yet proven as firmware behavior. Under this shim, the real builder then takes its Custom branch and emits bands matching captured gains. Both stock and community runs show the same code path and overlap.

The event `0x2604`, music-side selector/gains, SHM service return, module-`0x10` copies, MEngine copy/shared-memory/notification/ack effects, event helpers, and cross-overlay delivery remain synthetic. This establishes the payload-destination overlap with the selector after executing the state-base store, and demonstrates the result if the missing copy service has memcpy semantics. The separate callback-continuation probe reaches `FUN_60051034` and its next resident boundary only after directly invoking the observed callback under a scheduler hypothesis; normal boot/dispatcher flow reaching that callback is still unproven. Mainmenu block persistence is reported to occur on AP exit, but not on slider confirm/cancel; real service semantics, DSP consumption, and audible output remain unresolved.

## MEngine entry callback-registration boundary

`bash tools/ghidra/run_eq_mengine_init_registration_probe.sh <hash-matching-mengine.ap>` executes the real MEngine AP entry `FUN_60010d30` on stock V1.101.10 and community V1.292.00. In 53 p-code steps the entry requests a handle through resident selector `0x10`, issues selector `0x33`, then passes a stack record to resident selector `0x4a`. The record's first word is the MIPS16 pointer `0x60010d01` (`FUN_60010d00`), the wrapper that calls state initializer `FUN_60051278`; the other forwarded arguments match the caller-provided fields and synthetic handle. The AP instructions and record contents are observed in both variants.

The resident service calls are intercepted: selectors `0x10`, `0x33`, and `0x4a` receive synthetic return values. A separate, hash-pinned `run_eq_mengine_callback_continuation_probe.sh` invokes the observed callback address under an explicit scheduler hypothesis. On both stock and community images, the callback calls real AP state initializer `FUN_60051278`, issues resident shared-memory request `(a0=3,a1=0,a2=0,a3=0x49)`, and reaches `FUN_60051034` after the probe supplies synthetic return `0x9fc16000`. Continuing the real AP instructions reaches the next resident boundary at `0xbfc11cbc` with `(a0=6,a1=2,a2=0,a3=5)` after 99 p-code steps. Returning synthetic `0` or `1` from that service leads, in both images, to resident request `0xbfc11cc8(0,0,0,0x33)` at step 110. These two returns do not distinguish that immediate branch; other return values and service side effects remain untested. The probe stops before the `0x33` service. This proves the AP callback body reaches these boundaries when manually invoked with zero arguments; it does **not** prove op `0x4a` invokes it or establish runtime loader/scheduler behavior. Ghidra finds no direct code xref to `FUN_60010d00`; the pointer is supplied through the registration record instead.

There is a useful independent comparison in `mmm_bp.al`: its real initializer passes an op-`0x4a` record containing callback pointer `0xbfc27735`, a context pointer, a third field, and a 16-bit field `0x4b0`; the callback body is a looping worker-like function. That record strengthens the worker-registration hypothesis for MEngine, but its resident entry address is different, so it does not establish the MEngine service contract or prove callback invocation. See [the MMM_BP record probe](findings-mmm-bp-context-init-boundary.md#dynamic-entryinit-p-code-boundary).

## `mmm_mp.al` command `0x15`/`0x16` paired-copy probe

`bash tools/ghidra/run_mmm_mp_command15_probe.sh <hash-matching-mmm_mp.al>` executes the real `FUN_bfc13864` dispatcher for commands `0x15` and `0x16`. In both the stock and community archives, the input module has SHA-256 `57b541ad791637e30135db6c114da98d5680b5f0907ffabf74bf57fa4977a199`. Command `0x15` requests one 12-byte copy from its caller buffer into context `+0x12c`, then the real instructions mirror the first copied word to context `+0x104`. Command `0x16` requests one 12-byte copy in the opposite direction, from context `+0x12c` to its caller buffer. Both paths also request lock/unlock selectors `0x19`/`0x1a`.

The p-code probe passed on both byte-identical module copies. The dispatcher and field writes are real AP instructions; the resident copy and lock effects are synthetic shims, so the probe verifies the requested arguments/direction and resulting state under those shims, not resident-service semantics. At this point the 12-byte setter/getter shape alone did not establish a public API name or an EQ relationship.

Follow-up caller correlation narrows that interpretation: stock `mengine.ap` `FUN_60190eec` passes command `0x15` the pointer `0x9fc15d80`. In its fast-backward branch it writes the three words `(-5000,0,0)` at that address immediately before the call; the same function has a separate mode that sends `0x15` with the same record pointer. The independent MEngine/SDK trace identifies this command as `SET_BREAK_POINT`. The 12-byte payload and command-`0x15` context update therefore belong to playback breakpoint state, not EQ. Command `0x16` has the reverse copy shape and remains an unnamed getter candidate; no actual caller or public name has been established for it.

The bounded caller-set check is reproducible with:

```sh
bash tools/ghidra/run_mengine_breakpoint_command_callsite_probe.sh \
  analysis/stock/fw/mengine.ap analysis/community/fw/mengine.ap
```

It hash-pins stock V1.101.10 and community V1.292.00, decompiles
`FUN_60190eec`, and resolves every AP-side code reference to the dispatch
literal at `0x6019108c`. In both images all seven references are inside that
single handler; its breakpoint-setting calls use command `0x15`, and none use
`0x16`. This is a bounded negative result for this MEngine command bridge; it
does not prove that command `0x16` is unused by every overlay or assign it a
meaning.

## Music-side selector-7 callback probe (not the EQ Custom UI path)

`bash tools/ghidra/run_eq_custom_framework_callback_probe.sh <hash-matching-community-music.ap>` executes the community `music.ap` SHA-256 `662e379602707a9c4b0dcd6b94b591dd77c2b5f7a557fcb03b8c2a5c9b1baac3`. Real `FUN_403d4c1c` registers callback pointer `0x403d4c01` and flag pointer `0x9fc1543a`; the setter stores them in `0x9fc15908` and `0x9fc1590c`. Real `FUN_40c14f30` event-wrapper instructions call the event decoder helper and then dispatch its event through `FUN_487d44b0`. For event `0x2604` (dispatcher switch case 3), real dispatcher instructions check the pointed-to flag and indirectly invoke the registered callback. The probe seeds the event-decoder helper output and selector state, then observes the wrapper/dispatcher reach `FUN_403d4c00` → `FUN_40094d4c` → `FUN_403d4ed0`, return from the callback, and send `0x303:{7,7}`. `FUN_40c14c00` statically calls the event wrapper, but the probe starts at `FUN_40c14f30` and does not execute the parent event loop.

Evidence boundary: the event-decoder helper's success/result (`0x2604`), **music-side selector** value `7` at `0x9fc1570d`, and seven distinct stored gain bytes are synthetic inputs. This probe shows the internal producer path under those inputs; it does not identify the device UI route to that producer. Separately, the `music.ap` row `0x3301` maps to the volume-limit callback `FUN_40414c00`, not EQ Custom. The linked `0x303` probe's selector overlap is conditional on the synthetic memcpy shim; fixed-ROM copy semantics remain unproven. The mainmenu slider edit path is p-code exercised; a separate disassembly report places persistent block writes on AP exit, not confirm/cancel. Engine/DSP consumption and audible output remain open.

## A. EQ
- Factory `dae_cfg` source: hash-matched `config.ap` `FUN_40dd4d60` copies
  items `0x86..0x95` into the 16 eight-byte band slots at structure
  `+0x10..+0x8f` (each config item has a 4-byte header followed by the 8-byte
  record). `FUN_40dd4e0c` separately fills three conditional records at
  `+0xa2..+0xb9`; item `0xa6` supplies three u16 fields at `+0xba/+0xbc/+0xbe`.
  The raw records decode as `{frequency u16, Q u16, gain s16, flag u16}`; their
  units and purpose are unknown. See the [hash-pinned config/AP evidence](evidence/config-dae-factory-2026-11-24/README.md).
- Consequence, bounded to the known builder path: when `FUN_601d0d58` handles
  an EQ selector, it writes its seven EQ bands and zeroes slots 7–15, including
  selector Off. Thus the builder output replaces the factory values in those
  16 band slots for that submission. Whether/when the factory filter is active
  before that message, and whether it is FullSound or speaker compensation,
  remain unproven.
- DSP selection: `FUN_40dd4c00` loads state `+0x18/+0x19` from config items
  `0x96/0x97` (defaults 1/0). `FUN_40dd4fe8` maps state `+0x18 == 1` to
  `dae_cfg[+2]=1,+3=0`; otherwise it writes `+2=0,+3=1`, then creates the
  shared buffer (VERIFIED). MEngine requests `mmm_mp.al` by name; its
  `FUN_68312d00` requests `adppext.dsp` when shared `+2` is zero, and for
  nonzero `+2` requests `adppdrc.dsp` at or below 48 kHz or `audiopp.dsp`
  above 48 kHz (VERIFIED code and p-code filename requests). With default
  item `0x96=1`, the builder gate is open; the default DSP choice follows this
  rate-dependent rule only if the persisted DAE/settings blocks survive the
  ROM flash round trip and no intervening writer changes them (INFERENCE).
  `mmm_pp.al` has a different, simpler selector, but its runtime loader path
  is UNKNOWN. The open service is synthetic in the p-code probe; actual DSP
  loading, internal role and audible effect are UNKNOWN.
- Config item `0x98` defaults to zero and initializes DAE `+0x0c`; the MEngine
  builder returns early if its submitted structure's `+0x0c` equals one. The
  byte mapping and guard are verified, but the product meaning of this mode
  flag is unknown. `config.ap` writes the settings and DAE images under flash
  IDs `0` and `0xf0000`; `mengine.ap` reads those IDs into its own RAM blocks
  (VERIFIED decompile). The MEngine builder gate is settings-copy byte
  `0x9fc15db4` (`+0x18`), not the EQ selector; the builder also requires
  local DAE `+0x0c != 1` (VERIFIED code). These are separate RAM objects
  linked through the flash service; its actual persistence and intervening
  writes remain UNKNOWN. Decompiles: [factory fields](evidence/config-dae-factory-2026-11-24/factory.c), [state and mode fields](evidence/config-dae-factory-2026-11-24/state.c), [current kernel/DAE chain](findings-kernel-op-table.md#from-config-item-0x96-to-the-dsp-file-name).
- Reproducible check: `bash tools/ghidra/run_eq_probe.sh` decompiles the
  community `mengine.ap` EQ loader and checks its pointer pool, seven-band
  frequency table, gain rows, and the `setting.ap` Custom row. It is a static
  AP/raw-byte check and does not validate audible output.
- `bash tools/ghidra/run_eq_builder_probe.sh` executes the real MIPS16 builder
  in Ghidra p-code against hash-verified stock and community `mengine.ap`.
  Eleven cases pass; both binaries produce the same structure digest. The
  probe uses synthetic RAM and stubs for missing ROM services, so it validates
  builder writes and call arguments only.
- V SDK: ap/ap_setting/menu_callback_sound_setting.c: eq_param[8][MAX_EQ_SEG] int8 (none,rock,funk,hiphop,jazz,classic,techno,custom); _set_real_eq() builds mmm_mp_eq_info_t{eq_type, eq_para[12]} and sends MSG_MENGINE_SET_EQ_SYNC (music_set_eq, line 81-88). Engine: mengine_event_volume.c mengine_set_real_eq() -> mmm_mp_cmd(MMM_MP_SET_EQ,&eq_info). Custom = g_setting_vars.music_comval.eq_parameter[12] (int8, setting_common.h:167), edited in music_setmenu_soundset_param.c. EQ_TOTAL=8 (ap_setting.h:27) incl. Off. config.txt SETTING_AP_ID_EQ_SETTING = 0[0..7].
- V Table A (setting.ap, community==stock): file 0x5a94..0x5b13, 8 rows x 16 B (12 int8 used + 4 pad; rock row 0x5aa4, funk 0x5ab4, hiphop 0x5ac4, jazz 0x5ad4, classic 0x5ae4, techno 0x5af4, custom 0x5b04 = {-9,0,0,0,0,0,6}). Zeros 0x5b14..0x5bff (236 B) then next sector at 0x5c00.
- A Ghidra 12.2 `LoadAP` scan mapped this table to VA `0x40255294` and checked raw pointer literals plus Ghidra references to all eight row starts (`0x40255294..0x40255304`, stride `0x10`): zero hits. This finds no direct row reader in the loaded `setting.ap` banks. It does not prove the table is dead; a computed address, generic settings reader, or shared resident path could still consume it. The initial `0x38`-byte state initializer separately leaves its own Custom buffer (`+0x2c..+0x32`) untouched.
- V Table B (mengine.ap, same in stock): presets at 0x440c.., 7 B/row (7 signed-int8 gain values; row index is preset): Off 0x440c, Rock 0x4413, Funk 0x441a, Hip hop 0x4421, Jazz 0x4428, Classical 0x442f, Techno 0x4436 (selector 0..6). Frequencies are u16 values at 0x43ec (80,200,480,980,4000,8000,16000 Hz); Q values are raw u16s at 0x43fc (10,6,4,6,10,5,8). Calling those Q×10 is a hypothesis; the binary establishes the stored values only. Pointer pool file 0x41f4 = {0x601d10ec, 0x601d10fc, 0x9fc1a19c (RAM custom), 0x601d110c}; file = VA - 0x601ccd00.
- Corpus comparison: the 49-byte block `0x440c..0x443c` is byte-identical in the available stock, official, community, and derived firmware copies. Interpreted as signed int8, the rows are Off `(0,0,0,0,0,0,0)`, Rock `(5,3,0,-1,0,4,5)`, Funk `(3,0,0,0,0,-1,-2)`, Hip-hop `(0,0,1,2,0,1,1)`, Jazz `(3,0,0,-1,0,2,4)`, Classical `(0,0,0,-1,-1,-2,-4)`, and Techno `(5,2,0,0,0,0,6)`. Values span -4..+6. The following bytes through `0x47ff` are zero in those copies, but this alone does not prove they are available patch space.
- V `FUN_601d0d58` selects one 7-byte table row, or selector 7's seven RAM bytes at `0x9fc1a19c`, sign-extends each gain, multiplies by 10 and writes an s16 into each band. The ×10 conversion is verified; interpreting the result as 0.1 dB units is a strong inference, not a demonstrated DSP unit. Seven active bands use the eight-byte layout `{frequency u16, Q u16, gain s16, enable=1, reserved=0}` beginning at structure offset `0x10 + 8*i`.
- V Scratchpad MIPS16 p-code run of `FUN_601d0d58` used hash-verified stock and community `mengine.ap`; all 11 cases (selectors 0..7 with sentinel Custom, real Custom default, `+0x0c` guard, and inactive guard) matched their byte expectations. It confirmed the argumentless entry, selector mirror at `0x9fc15db5`, active flag at `0x9fc15db4`, and early return when structure `+0x0c == 1` after mirroring the selector. The field's config source is now known (`config.bin` item `0x98`); its product meaning is not. In an active build, `+7=1`, `+0xd=(selector != 0)`, bands 0..6 are written, and bands 7..15 occupy and zero `0x48..0x8f`. Bytes `0x00..0x06`, `0x08..0x0b`, `0x0e..0x0f`, and `0x90..0xef` retain their initial `0xaa` values in the probe. The latter range is untouched, not the unused-band range.
- V The builder passes `{a1=0x9fc15dd4, a2=0xf0}` to the module `0x10` copy stub, then requests shared-memory id 3 with argument `0x49`, then invokes module `0x42` with command `0x1f`. The probe used a synthetic nonzero SHM handle; the message target value was zero because initialization was not run. These stub effects are not modeled as DSP behavior.
- V Free space: mengine.ap zeros 0x443d..0x47ff (0x443d-0x4443 would be a would-be 8th row; 956 B to 0x4800 sector end, but 0x4600 is also a sector edge -> 0x4600..0x47ff may be bank padding). setting.ap 236 B after table.
- V/H Extra presets: `run_eq_probe.sh` confirms `FUN_601d0d58` has a seven-iteration loop, uses `idx * 7` for non-Custom rows, and branches only on `idx == 7` to the Custom RAM pointer. No index clamp or default case is visible in this function. The UI count remains `EQ_TOTAL=8`; setting.ap table and music/setting menu strings/.mcg trees are separate. Adding presets therefore needs a code patch (new index mapping and bounds behavior) plus menu tree/sty edits. Not checked: music.mcg/setting.mcg node count, which AP writes `g+0x41` (H: copy of eq_type from SET_EQ msg).
- `run_eq_writer_probe.sh` performs a separate Ghidra instruction scan over `setting.ap`, `music.ap`, and `mengine.ap`. It finds no decoded direct `sb/sh/sw` store using an immediate `0x41` (or `0x141`) offset. This is a negative boundary result, not proof that the selector is never written: the writer may copy a message/structure, use a register-derived offset, or live in a shared resident component. It is enough to reject a simple “patch the first `+0x41` store” approach.
- `run_eq_chain_probe.sh` closes the message-path gap. The public PD196 reference defines `MSG_MENGINE_SET_EQ_SYNC` as `MENGINE_BASE + 3 = 0x303`; in the M30 binary, `FUN_60090d64` dispatches `0x303` through the pool entry at `0x60091014` to `FUN_601d0f30`. That handler calls the resident memory-copy service with the message payload as its source, then calls the pool entry at `0x601d0f74`, whose raw value is `0x601d0d59` (the MIPS16 entry of `FUN_601d0d58`). The service's payload-to-memory effect is not established by the extracted MEngine p-code. The builder then reads the selector from `0x9fc15c88 + 0x41`, chooses Custom only for `idx == 7`, and builds the seven-band structure. The producer side is resolved by the companion `music.ap` probe below.
- `run_eq_producer_probe.sh` resolves that producer in `music.ap`: `FUN_403d4e20` forwards a two-byte `{eq_type, selector}` payload through `FUN_40614f08`, which sends message `0x303`. The wrappers emit `0,0` for Off and `7,1` through `7,6` for the six built-in presets. `FUN_403d4ed0` copies the seven custom gains and then emits `7,7`. This closes the selector-producer gap; adding a preset would require extending this dispatch/menu set and the engine's coefficient mapping, not merely editing the EQ table.
- The tested `music.ap` row `0x3301` is not an EQ Custom row. Its `music.sty` label `0x69` resolves to `Omezení hlas.` (volume limit); its callback `FUN_40414c00` edits the associated numeric setting and can emit a type-3 popup request with string `0x54` (`Nastaveno omezení hlas.!`). The p-code rejection result for that request is unrelated to the EQ slider path.
- The EQ labels in `setting.sty` resolve to Classical, Custom, Funk, Hip hop, Jazz, Rock, and Techno. The editor itself is in community `mainmenu.ap` (SHA-256 `1ef9b7a466a0f3a6bec8ce8654422c372fd68105ff94739077bfe34b435a1105`): `FUN_40154cec` selects EQ type and `FUN_40154f44` edits the seven Custom gains stored at mainmenu block `0x9fc155f4 + 0x2c`. On state-1 selection event `0x2c03`, resident `FUN_bfc165e0` reads the selected code from `0x9fc155d0 + 6 + 2*index`; code 3 returns `0x8505` and enters the EQ selector. Config item `0xe2` supplies default order `0,4,5,2,3,1`, so code 3 is fifth when the menu block is initialized. Its localized label and relationship to the labelled `setting.ap` EQ list remain unknown. The earlier `setting.ap` row mapping was off; do not identify its `0x1a01` row as Custom EQ.
- `run_setting_confirm_probe.sh` reaches a generic `setting.ap` confirmation request (`0x100a`, payload `0x48554680`); it is not evidence for the Custom EQ slider's confirm/save callback. Do not use that probe to claim the slider-to-persistence path is closed.
- The Custom producer's actual source is the `music.ap` pointer literal at file `0x86f8` / VA `0x403d4ef8`, whose baseline value is `0x9fc15720`; the function copies seven bytes through it into the engine-facing Custom buffer at `0x9fc1a19c` before sending `{7,7}`. Correction: `0x9fc15720` is **not** the `+0x20` field of the `0xf0`-byte block at `0x9fc15700`. Each overlay reads/writes its own `0x38`-byte persistent block (id `0`, length `0x38`, read op `0x12`, write op `0x13`) and takes its seven-byte Custom source from block offset `+0x2c`:

  | Overlay | Block base | Custom bytes (`base + 0x2c`) | Pool file offset | Reported persistence path |
  |---|---:|---:|---:|---|
  | `music.ap` | `0x9fc156f4` | `0x9fc15720` | `0x14cc` | `FUN_40054ce8`, `FUN_40294dc0` |
  | `config.ap` | `0x9fc15654` | `0x9fc15680` | `0x1694` | `FUN_40054f38`, `FUN_40054f84` |
  | `mainmenu.ap` | `0x9fc155f4` | `0x9fc15620` | `0x1490` | `FUN_40014d74` (load/default), `FUN_40014fac` (exit write) |
  | `setting.ap` | `0x9fc15408` | `0x9fc15434` | `0x1548` | `FUN_40055200`, `FUN_402550b4` |

  Pool words were checked directly in this session; the per-overlay read/write call chains and negative writer scan are from the Ghidra report and have not been independently rerun here. Each helper uses the same persistent API shape: `(base, id=0, length=0x38, op=0x12/0x13)`. `setting.ap`'s `FUN_402550b4` sends message `0x8001`, writes its block, then sets flag `0x57` before the exit loop; interpreting this as menu-exit persistence is an inference. In `music.ap`, the selector byte is at block `+0x19`. `config.ap` copies its block's `+0x2c` bytes to the engine-facing buffer `0x9fc1a19c` during startup. The separate `0xf0`-byte config block at `0x9fc15700` is not established as EQ storage. The `music.ap` patch redirects the `0x9fc15720` source to its fixed seven-byte curve at file `0x8700` / VA `0x403d4f00`, so it bypasses that stored source on the patched producer path. The probe checks the pointer and curve; it does not establish that the Custom menu dispatch reaches the callback or that the curve affects audible output.
- `tools/ghidra/run_config_eq_state_probe.sh` checks the `config.ap` startup/exit call chain against SHA-256 `5b5687c0…56357580` and executes the default helper in p-code. `FUN_40054ccc` reads a `0x38`-byte block at `0x9fc15654` and a separate `0xf0`-byte block at `0x9fc15700`, applies their magic/default handling, then copies seven bytes from `0x9fc15680` to `0x9fc1a19c`. `FUN_40054f38` issues the corresponding writes for both blocks. Its cold/invalid `0x38`-byte-block path clears the block and calls `FUN_40dd4c00`; the real helper was run in Ghidra p-code with a sentinel tail and left `+0x2c..+0x32` untouched. Combining that result with the caller's clear-and-copy sequence means the first seven bytes copied to the engine Custom buffer are zero when the persistent block is invalid. This establishes the cold-start buffer value, but not which M30 UI callback later changes any overlay's `+0x2c` source.
- `FUN_40dd4c00` maps config defaults into the persistent `0x38` settings block; its writes stop at `+0x26` and do not copy the seven-value table at `setting.ap` file `0x5b04` into Custom offsets `+0x2c..+0x32`. On the invalid-magic path the caller clears the full block first, so those Custom bytes remain zero. This explains zeroed Custom gains for a fresh/invalid block, but does not establish that the `setting.ap` table is unused elsewhere or that a valid saved block is zero. The config item layout has numeric and enumeration examples; names and semantics for most of the 121 items remain unknown. See the [config schema and state map](evidence/config-dae-factory-2026-11-24/README.md).
- The music-side selector byte `0x9fc1570d` dispatches value `7` to `FUN_403d4ed0`, which copies seven bytes and sends `{7,7}`. The known built-in callbacks write selectors `0..6`. Separately, the Custom editor in `mainmenu.ap` updates its own `0x38`-byte state block and calls `FUN_40154c78(7)`. How the mainmenu and music overlays hand state to one another remains untraced.
- `tools/ghidra/run_mainmenu_eq_slider_probe.sh` executes `FUN_40154f44` with synthetic key events. Increment changes gain 0 from 0 to 1 and sends module `0x0d`, command `0x303`, payload `07 07`. The decrement sweep from `-3..+3` left `-3` unchanged, but set every tested value `-2..+3` to `-3`; this disagrees with “decrement by one to a -3 floor.” Because the event-decoder ABI is inferred, it does not establish physical key behavior. A local cancel run independently restored the editor byte to 0 while leaving the engine-facing byte at 1, with no compensating producer message. UI/event calls, memcpy, and readiness remain stubs; this does not prove sound output. A separate disassembly report places persistent block writes on mainmenu AP exit, not slider confirm/cancel. See [`mainmenu-eq-slider-pcode-2026-11-24`](evidence/mainmenu-eq-slider-pcode-2026-11-24/README.md).
- A direct Ghidra code-reference scan found no xrefs to `FUN_40154cec` or `FUN_40154f44`; the mainmenu state-pointer table supplies the indirect route. Menu code 3 returns `0x8505` and enters the EQ selector; its default position is fifth, but its localized label remains unknown. The `0x38` block is written on mainmenu AP exit, not on slider confirm/cancel; power-loss behavior before exit is untested. The `music.ap` `0x3301` row and its popup must not be used as evidence for EQ Custom.
- The earlier PD196 analogy that labeled block `+0x2c` as SRS is not evidence about M30. Four M30 overlays use their own block `+0x2c` as a seven-byte Custom source. The mainmenu slider writer is identified; writers for the other overlay copies and the DSP's interpretation remain unresolved.
- The experimental V1.292 candidate combines this Custom source pointer and gain curve with the shared directory marquee timer changed from 80 ms to 50 ms. The initial curve `(4,3,0,-1,1,2,1)` was reported as too subtle, so the current curve is `(6,5,0,-2,0,4,3)`, a bass-forward shape within the corpus-wide observed gain envelope `-4..+6`. The bands are 80, 200, 480, 980, 4000, 8000, and 16000 Hz. This does not assign dB units or prove audibility. The candidate's member-level and full encrypted-HEX round-trip checks verify that only `music.ap` and `browser.ap` differ; `config.ap`, `config.bin`, and saved-settings member bytes remain identical. See [`release-candidate.md`](release-candidate.md#custom-eq--50-ms-experiment). Device execution is still required to establish behavior.
- `tools/emu/eq_menu_probe.py` verifies the built-in callback pointer topology in stock, community, V1.101.11, and V1.101.12: seven entries occur in the `music.ap` table at file `0xdeec..0xdf64`, and three groups in `music.mcg` reference those pointers at `0x2fea`, `0x3554`, and `0x3b90`. This probe does not identify the EQ Custom editor. The separately decoded `music.mcg` pointer `FUN_40414c00` belongs to the volume-limit row, not EQ Custom.
- `tools/sim/m30_model.py` keeps an evidence-backed `FIRMWARE_EQ_PRESETS` contract separate from the older five-band UI proposal. It models raw u16 frequency/Q tables, signed-int8 gain rows, exact selector payloads (`Off = {0,0}`, built-ins `{7,1..6}`, `Custom = {7,7}`), the observed signed-int8 ×10 conversion, and the verified builder writes while preserving unknown bytes. Custom curves require seven explicit values; the model does not silently treat the proposed UI curves as device coefficients.
- The public twelve-image corpus shows one verified revision-specific exception: the 2024-04-24 `mengine.ap` uses `50 Hz` for the first band while the other checked releases use `80 Hz`. `FirmwareEqProfile` exposes both the current profile and this dated profile without weakening the default contract.
- `tools/emu/eq_binary_probe.py` cross-checks a selected profile against raw bytes: `mengine.ap` frequency/Q tables and all seven non-Custom gain rows, plus the default seven-value Custom row at `setting.ap` `0x5b04`. The default profile remains the current host target; the 2024-04 profile is selected explicitly for historical corpus validation.
- `run_eq_chain_probe.sh` verifies the message boundary: `0x303` dispatches to
  `FUN_601d0f30`, which reaches the coefficient builder at `FUN_601d0d58`.
  `music.ap` supplies the selector from `0x9fc15c88 + 0x41`; the host model
  exposes this as `FirmwareEqCommand` while leaving the indirect DSP calls
  unresolved.
- V Shared-memory chain: `config.ap` creates id 3 with a 0xf0-byte buffer and
  copies the source structure; `mengine.ap` builds/copies the same 0xf0-byte
  structure. `mmm_mp.al` attaches id 3 during open and forwards its EQ command
  `0x1f` to module `0x0c`, op `0x12`, with `a0=1` and the SHM pointer as `a1`
  (VERIFIED decompile; ROM dispatch INFERENCE). `mmm_pp.al` independently
  attaches id 3 at object offset `+0x9c` and calls the same candidate op, but
  no runtime path that loads `mmm_pp.al` is known. Its `+2` selector chooses
  `adppext.dsp` at zero and `audiopp.dsp` otherwise; that rule applies only
  to `mmm_pp.al`. The `aud_dev` bank-5 routine reads sixteen 8-byte band slots
  from `+0x10..+0x8f` under `a0=1` (VERIFIED code); the ROM mapping from op
  `0x12` to table entry 18 and actual copy-service effect remain UNKNOWN.
- V `config.ap` sets DAE `+2/+3` from state `+0x18` before creating `dae_cfg`:
  item `0x96` default 1 selects `+2=1,+3=0`. `mmm_mp.al` then applies the
  rate-dependent DSP request rule above (VERIFIED code; default runtime chain
  INFERENCE). This does not establish DSP internals or audible EQ effect.
- V `aud_dev.drv` has two nonempty YQHX segments: the resident module stubs at
  file `0x300` (8 bytes → `0xbfc09f58`) and the 21-word table at file `0x200`
  (`0x54` bytes → `0x9fc09f00`). Table entry 18 is `0x2c149401`; the static
  unit bank table B decodes bank 5 at file `0x1f00`, size `0x600`, with
  MIPS16 window address `0x9400` (VERIFIED bytes). The generic bank-table
  assumptions in `inspect_drv.py` and `run_auddev_bank_probe.sh` do not
  establish the device's runtime bank loader. The reported static Ghidra analysis of these bytes shows
  an `a0=1` path that reads sixteen 8-byte bands from the shared-memory pointer
  and applies per-band adjustments. Treat the code interpretation as static
  evidence and the runtime bank-loading effect as UNKNOWN. The
  table-index-equals-op relationship remains a strong inference because the
  fixed-ROM dispatcher is unavailable; neither point proves audible DSP effect.
- The reported static disassembly says the `id=1` path iterates over 16
  records and calls an indirect helper at `0x98fc` with source
  `param_2 + 0x10 + 8*i`, destination `_UNK_98f8 + 0x94 + 8*i`, length 8, and
  flag 0. This shape is consistent with copying engine band records into an
  audio-device working structure. No device execution or hardware/DSP effect
  was tested; do not treat this copy boundary as proof of playback.
- Reported follow-up decompile of the same bank-5 candidate adds conditional
  `dae_cfg` groups: `+7` gates the 16-band EQ copy and its adjustments;
  `+8` gates speaker compensation; `+9..+0xd` gate other gain/limiter groups.
  Above 48 kHz the code clears three consumer-state fields; this does not
  establish that the whole EQ group is disabled. Config item `0x85`
  defaults to zero at `+7`, while the MEngine builder sets `+7=1` when it
  submits EQ data; the builder sets `+0xd` when selector is nonzero. Thus
  Off and a zero-gain Custom curve have identical seven-band values, but may
  differ through the reported `+0xd` group. This group's units and audible
  effect are unknown. The follow-up decompile and p-code run are retained in
  [aud_dev findings](findings-auddev-driver.md); their copy service is a
  synthetic candidate `memcpy` shim. The device's actual `cfg` base and ROM
  dispatch remain UNKNOWN.
- `tools/ghidra/run_auddev_eq_copy_probe.sh` executes this candidate bank-5
  path in Ghidra p-code using a hash-pinned driver and either synthetic `id=1`
  data or a supplied `0xf0`-byte structure. The real instructions issue 16
  calls through slot `0x98fc` with destination `0x9fc34094 + 8*i`, source
  `param_2 + 0x10 + 8*i`, length 8, and flag 0. A candidate `memcpy` shim
  copies between Ghidra emulation-memory addresses and the probe checks all
  16 destination bands against their sources. This validates the caller's
  envelope and data layout under that explicit service hypothesis; ROM
  service behavior, operation dispatch, DSP coefficients, and audible effect
  remain unknown.
- `tools/ghidra/run_eq_auddev_chain_probe.sh` runs the real `mengine.ap` EQ
  builder for Off, Hip-hop, default Custom, and the tested Custom candidate,
  exports each `0xf0`-byte
  result, and feeds it to the candidate `aud_dev` probe. The harness seeds the
  builder buffer with `0xaa`; therefore the seven active band records and
  zeroed inactive records are builder output, while the trailing adjustment
  area is a preserved harness sentinel rather than a firmware default. Each
  preset is checked with that sentinel, a synthetic zeroed adjustment area,
  and synthetic low/high threshold-and-gain settings. Ghidra p-code execution
  confirms the candidate consumer's 16 record transfers and resulting gain
  arithmetic for all twelve cases under a synthetic module-`0x10` memcpy shim.
  The runtime bank loading, ROM dispatch/service, actual runtime adjustment
  values, DSP use, and audibility remain unverified.
- Device feedback for the experimental V1.292 Custom-curve/50 ms candidate:
  the user reported no audible difference between `Custom` and `Off`; a
  supplied EQ-screen photo shows all seven displayed sliders at the 0 dB
  line. The exact listening conditions and a controlled built-in-preset
  comparison were not recorded. The image patch redirects the music producer's
  source pointer but does not change the separate settings/UI data path, so
  flat displayed sliders are compatible with the patched producer sending a
  different fixed curve. The exact `music.ap` on the device was not identified
  by a member hash or by reading file offset `0x86f8`; the report therefore
  does not establish whether the patched producer was active. It also does not
  establish whether its coefficients reached the audible output.
  Treat the device result as a negative report, not a verified DSP failure;
  resolve the message-to-aud_dev/DSP/hardware path before tuning gains again.
- Cheapest device-side discriminator: read `music.ap` at file offset `0x86f8`.
  `20 57 c1 9f` is the stock source pointer; `00 4f 3d 40` points to the
  experimental in-bank fixed curve. The current producer p-code probe starts
  at `FUN_403d4ed0` directly, so it validates the callback boundary but not
  whether selecting Custom in the menu reaches that callback.
- Data-only preset tuning is the lowest-risk firmware experiment currently
  identified, but it is not validated as safe. Existing gain values are signed
  bytes and the builder scales them by 10; interpreting them as dB is an
  inference. The experimental Custom curve was packaged and reportedly tried,
  but produced no perceived difference from Off; its test conditions are not
  sufficiently controlled to locate the failure. Do not tune another curve
  before resolving the producer-to-audio-consumer path. The Q table's physical
  units and the DSP's clipping limits remain unknown. The 50 Hz first-band
  value in the 2024-04-24 image proves that value was shipped in that image,
  not that it is safe on another revision or hardware path.
- V The module `0x42` stub, command `0x1f`, and EQ shared-structure references
  are present in `mengine.ap`. The earlier AP/driver/DSP scan omitted `.al`
  members: `mmm_mp.al` contains the command `0x1f` handler and MEngine requests
  that module by name (VERIFIED code/callsite; module-`0x42` binding and actual
  load remain INFERENCE/UNKNOWN). `coeffi.bin` uses a separate
  six-block loader (six blocks of 0x1a00 bytes); the EQ builder does not refer
  to it, and the EQ frequency table was not found inside the DSP blobs. An EQ
  connection to `coeffi.bin` is therefore unproven. Both `mmm_mp.al` and
  `mmm_pp.al` can request `audiopp.dsp` under different conditions; neither
  filename request proves that DSP consumes EQ data.
- V Independent Ghidra decompilation of `mmm_pp.al` (SHA-256
  `9a7a6c2215b2594e2ef816b8a3e592968921afff7747f3cadbd6e71bf74047d6`)
  confirms resident `FUN_bfc138c0` switches on its second argument. Case
  `0x11` calls banked `FUN_68013118` through the pointer at `0xbfc13a34`
  (`0x68013119`, MIPS16 entry); that handler calls module `0x0c`, op `0x12`,
  with `a0=1` and the object's `+0x9c` handle. Value `0x1f` is not a switch
  case and reaches the default error path. This rejects a direct
  `FUN_bfc138c0(event=0x1f) -> FUN_68013118` mapping. It does not establish
  a `mmm_pp.al` command-`0x1f` receiver. `mmm_mp.al` has a command-`0x1f`
  handler; the exact module-`0x42` dispatch binding remains an inference.
- V music.ap contains no eq table (no byte pattern match in any member except setting.ap, mengine.ap).

## B. usound
- `tools/ghidra/run_usound_setup_return_sweep.sh` runs the extracted
  `usound.drv` setup function to its stop point while replacing each of its
  11 fixed-ROM calls, one at a time, with return values `1` and
  `0xffffffff`. The remaining calls return zero. All 23 profiles (baseline
  plus 22 isolated-return profiles) stop after 1,368 instructions, preserve
  the same call sequence/arguments and copied rate bytes `80 bb 00`, and have
  the same PC+ISA-mode+RELP trace digest. This makes the probe fail closed on an
  emulator error, step limit, missing call, or changed caller trace. It only
  establishes that these return substitutions did not change the executed
  caller path in this setup; mapped MMIO is synthetic/zeroed, module returns
  are synthetic, and the module-`0x10` rate copy is performed by an explicit
  harness copy shim. Actual USB, DMA, ROM-service, and codec behavior remains
  unresolved. Reproduce with:
  `bash tools/ghidra/run_usound_setup_return_sweep.sh <hash-matching-usound.drv>`.
- `usound.drv` is now covered by two read-only checks:
  `PYTHONPATH=tools/emu python3 tools/emu/usound_binary_probe.py
  analysis/community/fw/usound.drv` validates the yqhx container and USB
  descriptor bytes; `bash tools/ghidra/run_usound_probe.sh
  analysis/community/fw/usound.drv` decompiles the resident code path. Both
  checks pass for the local community, stock, V1.101.11, V1.101.12, and V5
  copies because those members have the same SHA-256.
- `config.bin` is separately checked by
  `PYTHONPATH=tools/emu python3 tools/emu/config_usb_mode_probe.py
  analysis/community/fw/config.bin`; it resolves item `0x116` through the
  index at `0x8c0` to payload offset `0xe4a`, length 8, whose first u16 is
  zero (`00 00 00 00 01 00 01 00`). The earlier `0xe4a` wording refers to
  the payload, not the index record.
- V The embedded device descriptor is USB 1.1, VID `0x10d6`, PID `0xdd00`.
  The configuration advertises three distinct interfaces: audio control
  interface 0, audio streaming interface 1 (alternate settings 0 and 1),
  and HID interface 3. The audio format descriptor is two-channel, 16-bit
  PCM at the single advertised rate 48000 Hz. The streaming endpoint is
  isochronous OUT `0x01` with a 200-byte maximum packet size; the HID endpoint is interrupt
  IN `0x82`. The raw strings are `USB Audio` and `Actions`.
- The configuration topology is now checked by `tools/emu/usound_binary_probe.py`:
  the USB streaming input terminal (`0x0101`) feeds a feature unit and a
  `0x0301` speaker output terminal. This upgrades the USB-DAC conclusion from
  “an Audio Class endpoint exists” to “the device descriptor advertises a USB
  speaker/sink.” It still does not prove that the M30 connector is wired for
  device mode or that the unresolved callback reaches the physical codec.
- V The extracted `usound.drv` is byte-identical across the six local
  corpora (`community`, `first-pass-verify`, `stock`, `v1.101.11`,
  `v1.101.12`, and `v5`; SHA-256
  `e593bdd29c2a96d4d745cf1b5cae4da2ee71c2211c551e415209b18f54e600fc`).
- V A dated-corpus extension of that check covered nine additional extracted
  releases (2022-09-14, 2022-11-17, 2023-02-20, 2023-07-26, 2024-02-28,
  2024-03-19, 2024-04-24, 2025-02-17, and 2025-06-26). Every `usound.drv`
  has the same SHA-256 and passes `usound_binary_probe.py` with the same
  UAC1/HID descriptor contract. This strengthens the cross-version stability
  of the driver boundary, but still does not prove PCM ownership or OTG
  hardware behavior.
- V Ghidra maps 9,904 payload bytes into 46 functions. The decompiled
  initialization writes the 200-byte streaming packet size and `0x84` control
  value; the request handler has standard/interface control branches and
  handles endpoint `0x82`. This ties the descriptor table to executable
  driver code rather than treating strings alone as proof.
- V The fourth YQHX descriptor maps file `0x2900..0x2a53` to
  `0x9fc33c00..0x9fc33d53`. Its first 77 words are descriptor/config data;
  the final eight words at file `0x2a34` are code pointers
  `bfc3154d,bfc315f9,bfc31615,bfc31629,bfc31655,bfc31741,bfc31755,bfc318b9`.
  `usound_operation_table_probe.py` hash-checks this tail. Calls from
  `usound.ap` use module 5 op 3 with scalar index, buffer, and fixed lengths;
  the op-3 pointer at `bfc31629` reaches the parameter setter at `bfc31de8`.
  Direct indexing as `table[op]` is consistent with this and the independent
  `aud_dev.drv` call shapes, but the fixed-ROM dispatcher is unavailable, so
  the host candidate resolver marks this rule as an inference and never calls
  the candidate entry.
- A scratchpad Ghidra MIPS16 p-code run executed the real
  `FUN_bfc303c4` request handler with the `0xc0080000..0xc00803ff` MMIO window
  represented as zeroed RAM. It confirms the request-state behavior under
  those modeled registers: SET_CUR frequency writes the supplied 3-byte rate
  to `0x9fc33c00`, while GET_CUR frequency always returns the descriptor bytes
  at `0x9fc33ca3`, even after SET_CUR stores another requested rate.
  `SET_INTERFACE` resets the ring only for `bRequest=0x0b`, `wValue=1`, and
  `wIndex=1`; other alternate values are ACKed without resetting it. The run also exercises mute/volume
  GET/SET, HID idle, descriptor lookup, IN packet chunking, and the observed
  ACK/stall register writes. MMIO status transitions and real USB timing were
  not modeled, so these are software-handler results, not a device USB test.
- `tools/emu/run_qemu_usound_ep0_probe.sh` executes the actual
  `FUN_bfc303c4` MIPS16 handler under QEMU/Malta for valid SET_INTERFACE
  altsetting 1 and 0 requests. It confirms the handler copies both eight-byte
  setup packets, calls the ACK helper (whose MMIO target is RAM-backed), clears
  the four seeded state locations for alt 1, and preserves them for alt 0.
  The driver code is loaded at `0x8fc30000`; its direct MIPS16 calls therefore
  retain their original region bits. The handler's audited pointer literals
  and ACK pointer are remapped into guest RAM. These results validate the
  handler's software behavior under the harness, not USB enumeration, actual
  controller handshakes, malformed requests, or physical audio. The four
  locations are described as ring-state fields by current static analysis;
  this probe itself establishes only their clear/preserve behavior.
- `tools/emu/run_qemu_usound_ep0_stream_probe.sh` runs the real EP0 handler,
  producer `FUN_bfc30dc4`, threshold helper `FUN_bfc31390`, and DMA setup
  `FUN_bfc312e0` in one guest. After alt 1 clears the shared state, 74 producer
  calls each enqueue a seeded 192-byte packet. The full `0x3780` ring payload is
  checked. Crossing the `0x3720` threshold sets ready byte `0x9fc33e13`, runs
  the helper; before any later standalone DMA-setup subcase, the probe checks
  that this threshold path left ring address `0x80080000`, control `0x4b03`,
  and size `0x3720` in the RAM-backed register aliases. Alt 0 then ACKs while
  preserving the queue and ready latch. The probe maps the helper's
  `0xc000....` and `0xc005....` control literals to RAM-backed guest addresses;
  this tests real instruction flow and register-value logic, not physical MMIO
  semantics or a DMA transfer. After alt 0's preserve check, the probe calls
  real `FUN_bfc3125c` against that same queue: `0x3780 - 0x3720` leaves `0x60`,
  ready remains set, and a synthetic clock value `0xa5` becomes `0xa4`. Control
  bit OR behavior is checked in the separate completion boundary matrix. This
  completion call is injected by the harness;
  no hardware interrupt or DMA completion is observed. The earlier 71-packet
  timeout was a probe failure: its stale assertion expected the ready latch to
  remain clear, then entered the failure halt loop. USB/FIFO delivery, actual
  completion signaling, downstream consumer, and DAC remain outside the test.
  Reproduce with `bash tools/emu/run_qemu_usound_ep0_stream_probe.sh
  <hash-matching-usound.drv>`.
- `tools/emu/run_qemu_usound_rate_state_probe.sh` compares the real producer and
  threshold/DMA-setup functions after manually seeding the rate bytes and flags
  observed in the Ghidra `SET_CUR` probe. With the hash-locked `usound.drv`,
  both 48 kHz and 32 kHz runs
  passed: the three rate bytes differed as expected, while the 74-packet ring
  payload/checksum, byte count, ready latch, DMA address, control, and size were
  identical. Thus the tested producer/threshold path does not observably use
  those seeded rate bytes. The harness does not invoke EP0 `SET_CUR`, the
  consumer/module-`0x10` service, or a physical DAC; it cannot establish
  end-to-end rate support or lack of rate use elsewhere. Reproduce with
  `bash tools/emu/run_qemu_usound_rate_state_probe.sh <hash-matching-usound.drv>`.
- `tools/ghidra/run_usound_interface_probe.sh` now replays the real
  `SET_INTERFACE` handler from the hash-locked `usound.drv` in Ghidra p-code.
  With synthetic MMIO, alternate setting 1 clears the producer/consumer
  offsets, queued-byte count, and reset signal; alternate setting 0 is ACKed
  while those state fields retain their sentinels. This is a narrow
  reproducible part of the broader scratchpad EP0 run, not a USB-controller
  or hardware timing model.
- The same hash-locked p-code probe also runs Feature Unit `GET_MIN`, `GET_MAX`,
  and `GET_RES` (`bRequest` `0x82`, `0x83`, and `0x84`). It checks the returned
  two-byte values `0xe3a0`, `0xfff0`, and `0x0030`, the two-byte transfer length,
  and the ACK bit. `tools/sim/usb_audio.py` exposes this fixed observed mapping;
  the host model does not claim to emulate setup-packet routing or USB I/O.
- The tracked probe also round-trips five current controls through the real
  `FUN_bfc303c4` code: mute for entities `0x09` and `0x0b`, and master/left/right
  volume for entity `0x09`. Each SET_CUR payload is checked at its recovered RAM
  address, then GET_CUR must return the same bytes. The host model stores only
  these observed entity/selector slots and leaves their initial values unknown.
- The descriptor patcher's firmware-info path now has a tracked p-code case.
  The first module `0x1b` call is `(buffer, 0x4f, 1)`; its status is read from
  the output buffer, not `v0`. With status byte `0x02`, the real code calls
  `(0x9fc33d12, 0x50, 0x10)`. Supplying `M30-SERIAL-12345` there produces a
  34-byte USB string descriptor at `0x9fc33d10` (`0x22`, type `3`, UTF-16LE).
  With status `0`, the second call is skipped and the original serial index and
  descriptor bytes remain unchanged. With status `2` and sixteen zero bytes,
  the serial index is cleared and the in-place serial payload becomes zero;
  the existing `0x12, 0x03` descriptor prefix remains. These are synthetic ROM
  responses, so the real device status and serial value remain unknown.
- `tools/sim/usb_audio_stream.py::UsbAudioStream.set_interface()` carries that
  result into the host stream model. It resets the corresponding offsets,
  available-byte count, and reset signal for alternate setting 1, while
  preserving PCM storage and the short-read counter. Other alternate settings
  for interface 1 leave the stream state unchanged; other interfaces remain
  unmodeled.
- `tools/sim/usb_audio.py::patch_usound_descriptors()` now models the verified
  descriptor writes. Its hash-checked reproduction command is
  `python3 tools/emu/usound_descriptor_patch_probe.py
  analysis/community/fw/usound.drv analysis/community/fw/config.bin`. It
  produces VID/PID `10d6:dd01`, `M30`/`USB AUDIO` strings, interface count 3,
  16-bit/48 kHz fields and a 200-byte packet maximum from the stock config values.
  Unit cases also cover empty ID overrides, empty strings, interface flag 0,
  and item `0x115=1` selecting 24-bit/96 kHz. Item `0x116` remains the separate
  app-mode selector and is not consumed by the descriptor patch.
- `tools/ghidra/run_usound_descriptor_patch_probe.sh` now executes the real
  MIPS16 `FUN_bfc31f94` against four synthetic config sets, with the
  `usound.drv` SHA-256 pinned to `e593bdd2…e600fc`. It checks VID/PID, both
  generated UTF-16LE string descriptors, interface count, bit depth, sample
  rate, and endpoint packet size for stock values, 24-bit/96 kHz, a cleared
  interface flag, and empty VID/PID/string values. Only the ROM copy and
  firmware-info calls are stubbed; configuration values are synthetic, so
  this validates the patcher's writes rather than ROM config-item retrieval.
- `RunUsoundInterfaceProbe.java` also runs the real `FUN_bfc303c4` SET_CUR /
  GET_CUR sample-rate path against the stock MIPS16 bytes. SET_CUR's three-byte
  OUT data comes from MMIO `0xc0080140`, is stored as the requested rate at
  `0x9fc33c00`, and sets the two observed state flags. GET_CUR returns the
  descriptor's rate bytes from `0x9fc33ca3` even after the request stored a
  different rate. Reproduce with `bash tools/ghidra/run_usound_interface_probe.sh`;
  the probe uses 32 kHz SET_CUR against a 48 kHz descriptor.
  `UsbAudioControlState` models this distinction. It does not model USB
  controller timing or prove that the hardware accepts the request.
- Consequence: the firmware contains a device-side UAC1/HID implementation,
  so “M30 as a USB DAC for a USB host” is statically plausible. This does not
  prove that the M30 hardware exposes OTG/VBUS correctly, that the firmware
  can reach the path with the stock configuration, or that the device can
  act as a USB host driving an external DAC. No device write or USB-mode test
  has been performed.
- `usound.ap` is now covered by a binary bank probe and a Ghidra AP probe:
  `FUN_40094e88` first loads `usound.sty` and `common.sty`, then, on its
  nonzero setup branch, calls the helper with the packaged `usound.drv`
  name at runtime address `0x4009502c` and handles `install_err` at
  `0x40095038`. This proves an application-side conditional driver-install
  path. It does not identify the helper's full ABI and does not yet prove
  that PCM samples are handed to the driver.
- V SDK has no usound. APP_ID_UDISK=0x12 in SDK (case_type.h:55); config_main.c:80-95 MSG_USB_STICK -> gui_usbconnect -> create APP_ID_UDISK (online_device==0) else MTP.
- V manager.ap name table 12-B entries at 0x2634: music0 browser1 record2 radio3 setting4 user1 user2 linein7 manager8 mainmenu9 playlist10 config11 udisk12 mengine13 fmengine14 alarm15 mtp16 upgrade17 usound18 (0x270c). manager FUN_7f0d6200 (manager_community.c:231) accepts app ids <0x13. H: id==index, so usound=0x12, udisk=0xc, mtp=0x10 (consistent with config values below).
- V config.ap FUN_40054c00 (config_community.c:55-98), msg 0x90 case: if (*0x9fc155d4==1) data0=0x12 (usound); else if (byte(*0x9fc15654+0xf)==0) data0=0xc (udisk); else 0x10 (mtp); then sends msg 0x201 {data0,param 1}. Pool at file 0x14b4 (addr = file+0x40053800).
- V Flag 0x9fc155d4 written once in FUN_40054ccc init (config_community.c:134-137): *flag = get_config(0x116). The exact 32-bit literal `0x9fc155d4` occurs in config.ap at file offsets 0x14bc and 0x16bc. `mainmenu.ap` separately loads base `0x9fc155d0` and accesses `+4` as a lookup-table count; that computed address is numerically the same byte but belongs to a different overlay structure (see address caveat below), not a proven read of config item 0x116. config.bin id 278 entry (offset 0xe4a) = u16 (0,0,1,1) (community==stock); H: first u16 = default 0, so flag=0 -> usound branch dead by default; H: setting config id 278 default to 1 would make USB cable plug launch usound.ap (and skip udisk) - untested, and it is a config.bin change, no UI.
- Independent Ghidra decompilation of the current community `config.ap` (SHA-256 `5b5687c0…7580`) confirms the `FUN_40054ccc` initializer and `FUN_40054c00` message-`0x90` branch: the latter reads the byte at `0x9fc155d4`; value 1 selects manager app id `0x12`, while other values use the online-state byte at `0x9fc15654+0x0f` to select `0x0c` or `0x10`, then emit message `0x201`. This verifies config-app routing logic only; it does not prove connector/VBUS behavior or that `usound.ap` reaches an audible hardware path.
- Address caveat: `mainmenu.ap` has a different overlay function at the same numeric VA `0x40054c00`. Its code interprets `0x9fc155d0` as a lookup-table header (reads a 16-bit count at `+4`, scans 16-bit entries from `+6`, with a limit of 7); that is not evidence that mainmenu consumes config item `0x116`. The overlays have separate code and state lifetimes, and no transfer of the config selector into this mainmenu table has been established.
- The host selector model in `tools/sim/usb_mode.py` mirrors this exact branch: config item `0x116 == 1` selects `usound` before the online-device check; otherwise the normalized online-device state selects `udisk` or `mtp`. It is intentionally not a flash patch and does not claim that the M30 connector exposes the required USB device/VBUS path.
- `tools/emu/usound_dac_link_probe.py` adds a read-only cross-check of the next boundary. Across the six local `usound.drv` members there is no direct pointer into the separate `drv_dac.drv` resident range (`0xbfc18d00..0xbfc194ff`); the USB member instead retains the observed `0xbfc326a9` callback and `0xc0080084`/`0xc0080088` word-port pool. This is useful negative evidence only: an indirect resident callback or hardware DMA path could still connect USB samples to playback, so a config-only USB Audio switch is not yet a proven DAC-output feature.
- V No string "usound" in config/mainmenu/setting/music/udisk .ap; only manager.ap (0x270c). usound.ap loads usound.sty/common.sty/usound.drv (usound.ap 0x1114..). MAINMENU_AP_ID_ARRAY (config.bin id 1) = (1,5,0,1,2,3,4) -> no usound entry.
- The AP probe maps the packaged names more precisely: file `0x1114/0x1120/0x112c` maps to runtime `0x40095014/0x40095020/0x4009502c` in the `0x40094c00` bank. The setup branch is conditional; its condition and the subsequent PCM transfer ABI remain unresolved.
- The driver's declared YQHX entry at `0xbfc32680` returns zero. A separate candidate eight-pointer table at file offset `0x2a34` contains `0xbfc315f9` in slot 1; that MIPS16 pointer resolves to `FUN_bfc315f8`, which calls the pointer stored at `0xbfc31610`. The raw pointer value is `0xbfc322e5`, resolving to `FUN_bfc322e4`. The table bytes, pointer, and code are identical across all six local `usound.drv` corpora. This establishes an in-driver indirect route to the setup function, but not that the AP loader or fixed-ROM dispatcher invokes this table slot, nor when it would do so.
- Community `usound.ap` (SHA-256 `e67af258…afa96cf`) decompilation narrows its
  application-side handoff. In `FUN_40094e88`, `FUN_40094d88()==0` calls the
  opaque callback slot at `0x40095088` with `param_1`; the other branch
  conditionally installs `usound.drv` and enters `FUN_400950c0`. That event
  loop ends through another opaque callback slot at `0x40095228(param_1)`.
  Neither visible callback tuple carries an explicit PCM buffer, sample rate,
  or byte count. `FUN_40095228` still crosses unresolved instructions around
  `0x40095246`/`0x4009524c`, and no AP-to-`FUN_bfc31654`/module-`0x10` path was
  established. This narrows the missing boundary to indirect callbacks or
  resident services; it does not prove that the application has no audio-data
  handoff.
- A raw-pointer scan finds four uses of resident entry `0xbfc166ad` in the
  hash-pinned `usound.ap`; Ghidra decompilation identifies their callsites as
  `FUN_40054c00`, `FUN_40094c00`, `FUN_40094e88`, and `FUN_400950c0`. Their
  fourth arguments are respectively operation selectors `3`, `6`, `1/2`, and
  `7/0/6`; the operation-3 calls enumerate scalar indices 0..5 and copy
  lengths 6, 6, 8, 16, 1, and 1. No operation-4 call occurs in these AP
  callsites. If the current inferred module-5 `a3=operation` interpretation
  holds, the AP does not directly request `FUN_bfc31654` through slot 4. This
  does not exclude a fixed-ROM callback, another app, or computed dispatch.
  Reproduce the AP/DRV corpus scan with
  `python3 tools/emu/usound_resident_ref_scan.py . --value 0xbfc166ad`; inspect
  the callsites with `bash tools/ghidra/run_usound_ap_probe.sh
  analysis/community/fw/usound.ap`.
  A repository-wide scan of 346 `.ap`/`.drv` artifacts found this raw pointer
  only in byte-identical `usound.ap` copies (12 paths across extracted and
  build trees; SHA-256 `e67af2588eea49bf1f81a588ac252636e3678a756d39013e46605cc16afa96cf`),
  at offsets `0x9ac`, `0xdc8`, `0x117c`, and `0x1308`. No other app or driver
  has a raw little- or big-endian occurrence. This rules out a direct literal
  reference in that file set, not computed dispatch or code outside AP/DRV
  artifacts. Since the selector mapping remains unproved, the slot-4 question
  now depends on the unavailable fixed-ROM handler or a matching M30 ROM
  readback; more scans of the same AP/DRV copies cannot settle it.
- The callback slots are now verified against the raw, SHA-pinned
  `usound.ap`: file offsets `0x1188` and `0x1328` map to `0x40095088` and
  `0x40095228`, and both words contain MIPS16 pointer `0x40054de1` (target
  `FUN_40054de0`). Ghidra decompilation shows that this target prepares a
  message with ID `0x201`, derives a small status payload from `param_1` and
  opaque state helpers, and returns status values `0x800f` or `0x861c`. The
  send helper is also resolved: `FUN_48494758(8, &message)` looks up active
  app endpoint ID 8 with `FUN_48494640`, then invokes resident pointers
  `0xbfc16405` with `(local_buffer, message_pointer, 8, 0)` and `0xbfc16401`
  with `(endpoint_id, local_buffer, 0, 0x18)`. The first resident call's
  semantics are unresolved; its arguments do not establish a memory copy.
  In this firmware's app table, ID 8 is `manager`; manager handler
  `FUN_7f196200` explicitly dispatches message `0x201` and branches on the
  first payload byte. This supports a candidate route from both usound callback
  slots to that manager handler, but the opaque resident service prevents
  proving delivery. No PCM pointer, sample buffer, or byte count is constructed
  in the callback/sender path. The evidence strongly favors a
  lifecycle/control-status callback over the audio-data handoff, while the
  resident service ABI and any separate audio-data handoff remain unresolved.
  Reproduce with
  `bash tools/ghidra/run_usound_ap_probe.sh <community-v1.292-usound.ap> <community-v1.292-manager.ap>`;
  the binary probes pin both AP hashes and mappings, then Ghidra checks the
  callback, endpoint lookup/sender, and manager message handler.
- A hash-pinned dynamic follow-up now executes `FUN_40054de0` and
  `FUN_48494758` in Ghidra p-code, then runs the real manager
  `FUN_7f196200` against the captured 24-byte sender buffer. Five callback
  input/state combinations produce message ID `0x0201` and payloads
  `0x1100`, `0x1000`, `0x0e03`, `0x0f09`, and `0x4f0a`, matching the callback's
  distinct control/status branches; the same runs return `0x800f` for the
  first four cases and `0x861c` for the state-bit case, matching the function's
  two return branches. The real endpoint-lookup code selects the probe's
  synthetic active app-ID-8 record, and the real sender reaches the two
  resident-call boundaries with the expected argument shapes. The first
  resident call is modeled as an explicit synthetic eight-byte copy; the
  send/delivery call returns synthetically. At that send boundary, bytes 0..7 match the copied
  callback message, while bytes 8..23 are zero from the harness's initialized
  RAM; this is not evidence for the firmware's actual 24-byte message body.
  That synthetic 24-byte buffer is then injected into the separate manager
  p-code run. With manager state byte `0x9fc1a1b1`
  seeded to match message byte `+4`, the real `0x201` handler takes its
  equal-state path and issues module-`0x0c` operations 7 then 2; these
  resident returns are also synthetic. This raises confidence that the
  callback is a lifecycle/control-status path rather than the PCM handoff,
  but does **not** verify the ROM copy/send ABI, actual AP-to-AP delivery,
  event path, USB hardware behavior, or audio output. Reproduce with
  `bash tools/ghidra/run_usound_manager_message_probe.sh <hash-matching-usound.ap> <hash-matching-manager.ap>`.
- A separate, stronger driver-side fact is now verified in `usound.drv`:
  MIPS16 function `FUN_bfc31654` copies from a 0x1000-byte circular
  region, splits a wrap into two callback copies, advances a read pointer, and
  tracks repeated insufficient-data events (reset after 100). This is a
  stream-buffer-shaped ABI. Its literal pool resolves the state to ring base
  `0x9fc34000`, read pointer `0x9fc33dec`, available-byte counter `0x9fc33df0`,
  short-read counter `0x9fc33df4`, reset signal `0x9fc33e13`, and callback entry
  `0xbfc326a9` (odd MIPS16 pointer). The aligned callback entry at
  `0xbfc326a8` is the MIPS16 pair `li v1,0x10; break 0`; the adjacent
  `0xbfc326ac` pair uses module `0x0f`. A QEMU exception-vector probe
  executes the `0x10` target across all six local corpora and verifies that
  the ring consumer's four arguments survive the trap boundary. This resolves
  the callback's syscall envelope, not the kernel service or PCM ownership;
  the host emulator therefore models the ring only as an explicitly generic
  boundary, not as the firmware's PCM/DMA implementation.
- `tools/sim/usound_callback.py` now mirrors the proven part of that boundary:
  it records the module ID and four trapped arguments, counts module `0x10`,
  and refuses to execute either the fixed-ROM service or an unknown module.
  This is a host observation model, not a PCM/DAC implementation.
- The callback QEMU probe now records the CP0 exception envelope as well as the
  arguments. The current run reports exception code `9` (breakpoint),
  `EPC=0x800526ab` (the relocated odd MIPS16 callback entry), `BadVAddr=0`,
  and `Status=0x2`, then returns through the interrupted continuation. This
  proves that the synthetic vector receives a genuine MIPS breakpoint trap;
  it does not identify the unavailable fixed-ROM service that the stock vector
  would invoke. Re-run it with `bash tools/emu/run_qemu_usound_callback_probe.sh`.
- The module-`0x10` arguments from `FUN_bfc31654` match the independent
  `aud_dev` caller's candidate copy shape `(destination, source, length, 0)`.
  `tools/emu/run_qemu_usound_chain_probe.sh` now tests that hypothesis with an
  explicit exception-vector `memcpy` shim: it checks the USB consumer's
  destination and length, copies 16 synthetic ring bytes into a sentinel-filled
  destination, and verifies the destination byte-for-byte afterward. The real
  consumer and argument tuple execute in QEMU; the copy shim is synthetic. This
  demonstrates a consistent candidate ABI, not that the unavailable ROM
  module-`0x10` service performs this copy or that the data is PCM/DAC input.
  The two ring addresses remain deliberately aliased.
- A second independent caller strengthens the candidate meaning of this
  module `0x10` operation. In community `playlist.ap` (`FUN_48014694`), the
  code first normalizes a text key, then calls the resident module-`0x10` entry
  at `0xbfc16409` with `(destination, normalized_scratch, output_length, 0)`.
  The surrounding caller uses that result as its destination sort key. This
  strongly supports operation 0 as a copy and matches the USB consumer's
  observed `(destination, ring_source, byte_count, 0)` tuple. The resident
  implementation remains unavailable. Reproduce the AP-side cross-check
  with `bash tools/ghidra/run_library_record_probe.sh
  <community-v1.292-playlist.ap>` and the USB-side caller with
  `bash tools/emu/run_qemu_usound_chain_probe.sh
  <community-v1.292-usound.drv>`; the latter verifies the dataflow under a
  candidate copy shim only, not the actual ROM service or DAC ownership.
- `m30_preview_session.py` can now carry an explicitly supplied exception frame
  into its JSON trace with `--callback-exception CODE EPC BADVADDR STATUS`.
  The values are optional and the field is omitted when no context is supplied.
  The trace labels its input as caller-provided; the preview does not synthesize
  CP0 state or execute the missing fixed-ROM service. An optional
  `--callback-ra` adds a separately labeled `ker_xsys_entry_candidate` with
  the proven EPC/RA stores and jump target. This candidate does not claim the
  captured callback traversed that entry unless runtime evidence supplies the
  connection.
- `tools/emu/m30_preview_session.py` composes this callback observation with
  the real-resource now-playing renderer, recovered LCD command sink, and the
  bounded 200-byte USB-audio stream. Its JSON trace explicitly labels the
  session as hybrid and keeps the fixed-ROM callback effect unexecuted; the
  `--submit-packet` input is a framing test, not a claim of audible playback.
- A second driver boundary is also verified: `FUN_bfc318cc` transfers whole
  words through MMIO data port `0xc0080084` (mode 2 read, mode 1 write) or
  alternate port `0xc0080088` (mode 4 read), gated by `0x9fc33e12`. Its caller
  `FUN_bfc30dc4` prepares the 0x1000-byte scratch area before this transfer and
  then feeds a separate 0x6e40-byte software-ring path. The host model exposes
  only the observed word-port modes; it does not assign USB or PCM semantics to
  these registers.
- The two software-ring copy helpers are now distinguished: `FUN_bfc30cac`
  copies bytes directly, while `FUN_bfc30ce8` consumes complete 3-byte groups
  and emits `[0, b0, b1, b2]` 4-byte groups. The caller's format-1 size
  arithmetic is `byte_count * 4 / 3`; other formats use the direct byte count.
  This is an evidence-backed format boundary, not yet a claim about sample
  encoding or USB endpoint ownership.
- The producer-side software ring is now modeled separately at capacity
  `0x6e40`: write position `0x9fc33de8`, available count `0x9fc33df0`, direct
  ready threshold `0x3720`, expanded-format threshold `0x1b90`, and the
  observed overrun rule `available > capacity -> available = wrapped write
  position`. The producer sets the shared ready latch `0x9fc33e13` when the
  format-specific threshold is crossed. The host model covers direct and
  wrapped writes plus that ready edge; hardware FIFO timing and the downstream
  reader remain outside the model.
- A second p-code run executed `FUN_bfc3125c` against synthetic MMIO. It subtracts
  `0x3720` from the available count and ORs `0x404` into the DMA control word.
  A signed result below 1 takes the empty path, zeroes available, clears
  `0x9fc33e13`, and calls `FUN_bfc314e0`, which writes `0x80` to `0xc005001c`.
  The host state model now clears the same ready latch on this path, allowing a
  later threshold crossing to raise a new edge.
  The stop meaning is inferred from the nearby `dac_dma_stop1` string. For
  nonempty results it clears the
  low two clock-control bits below 4410, preserves them from 4410 through 9702,
  and sets both at 9703 or above. The function does not read the format flag;
  thresholds apply to the post-subtraction count. The host ring model now carries
  these transitions. MMIO was plain RAM and the ROM service was stubbed, so this
  verifies code branches and writes, not physical clock or DMA effects. A later
  QEMU run executes the actual MIPS16 handler with the same six remaining-count
  boundaries using RAM-backed MMIO. Its direct call to `FUN_bfc314e0` is retargeted
  to the relocated guest code, where a counter-and-return stub replaces that helper; the
  state and threshold logic is verified, but the helper's hardware shutdown work
  is not.
- In the hash-pinned `usound.drv`, the function-pointer slot at `0xbfc324c0`
  contains `0xbfc326ad`, an odd MIPS16 pointer to the local module-`0x0f`
  break-0 stub at `0xbfc326ac`. At that entry the bytes `0f 6b 05 e8` decode as
  `li v1,0x0f; break 0`; the adjacent entry at `0xbfc326a8` is
  `li v1,0x10; break 0`. A QEMU exception-vector probe executes both actual
  stubs across all six local driver copies and captures their four arguments;
  its module-`0x0f` call is harness-injected and does not execute the setup
  caller or fixed handler.
- `tools/ghidra/run_usound_setup_probe.sh` separately executes the real
  `FUN_bfc322e4` MIPS16 instructions in Ghidra p-code. Synthetic MMIO is
  RAM-backed, and module stubs are intercepted with `v0=0`. It observes ten
  module-`0x0f` calls and one module-`0x10` call. The actual module-`0x0f`
  tuples include `(a0=0x14, a1=0xbfc3125d, a2=0, a3=0x14)` and
  `(a0=0x0a, a1=0xbfc31115, a2=0, a3=0x14)`. This corrects the earlier
  interpretation that `a1` was the address `0xbfc324ec`: the value passed is
  the odd MIPS16 function pointer stored there. The first target is
  `FUN_bfc3125c` (file offset `0x145c`); the second is `FUN_bfc31114` (file
  offset `0x1314`). The former `0x26ec`/`0x26e8` values are the file offsets of
  the pointer slots, not the function bodies. The data at
  `0xbfc324e8..0xbfc324ff` also contains
  `0xc008018c`, `0xc0080198`, `0xc008019d`, and `0xc0080191`, but these words
  are not passed by this call. A separate key-driver finding maps module
  `0x0f` operations `0x18`, `0x19`, and `0x1a` to message-queue operations,
  supporting the interpretation that `a3` selects a service operation. Here,
  setup calls operation `0x14` twice, pairing `a0=0x0a`/`0x14` with callback
  pointers in `a1`; the return value is not consumed. The strongest current
  hypothesis is that operation `0x14` installs callbacks keyed by `a0`, but
  neither that behavior nor the event meanings are proven without the fixed-ROM
  handler. This is not evidence of a DMA interrupt vector or a verified
  runtime dispatcher. No fixed-ROM handler or device was run.
- The same setup probe now retains the `a0=0x0a` callback pointer from the
  actual call arguments. After setup returns, it injects event bit `0x02`
  into RAM-backed `0xc0080191`, invokes that retained pointer, and follows
  the real callback instructions to the entry of `FUN_bfc30dc4`. This closes
  the setup-pointer-to-producer code path under an explicitly synthetic
  dispatch. It does not establish that the resident service installs or
  invokes the callback, or that the physical USB event has this meaning.
- The first passed function pointer, `0xbfc31115`, resolves to
  `FUN_bfc31114`. Its static body checks the word at `0xc0080191`; when bit
  `0x02` is set, it writes the observed mask value to `0xc0080400` and calls
  `FUN_bfc30dc4`, the USB FIFO-to-ring producer. The other branch can call the
  pointer at `0xbfc3119c` (`0xbfc303c5`). This makes `FUN_bfc31114` a plausible
  producer/event handler, but the hardware-bit meaning and whether module
  `0x0f` dispatches this pointer remain unverified.
- `tools/emu/run_qemu_usound_event_dma_probe.sh` now enters the real
  `FUN_bfc31114` callback 74 times and follows its real MIPS16 call into
  `FUN_bfc30dc4`. The RAM-backed event bit is set before each callback and the
  producer status is cleared per packet; 192-byte seeded packets advance the
  producer write offset and available count to `0x3780`, set ready, and execute
  the actual `FUN_bfc31390`/`FUN_bfc312e0` threshold path. QEMU observes DMA
  ring address `0x80061000`, channel control `0x4b03`, and size `0x3720`.
  Firmware address `0x9fc24000` (DMA ring) and `0x9fc34000` (staging area,
  also used as the consumer ring) are mapped to distinct guest regions; this
  probe does not execute the consumer. The direct MIPS16 call is preserved by
  mapping the driver at `0x8fc30000`. The event/MMIO aliases and input bytes are
  synthetic; the module-`0x0f` dispatcher, USB FIFO, physical DMA, module-`0x10`
  service, consumer handoff, and audible output remain unverified. Reproduce
  with `bash tools/emu/run_qemu_usound_event_dma_probe.sh
  <hash-matching-usound.drv>`.
- `tools/emu/run_qemu_usound_composition_probe.sh` executes the real event
  callback, producer, word-port helper `FUN_bfc318cc`, then explicitly calls
  consumer `FUN_bfc31654` from the harness. That consumer call edge is
  synthetic; it is not a recovered firmware route. A Ghidra dump of the 46
  heuristic functions found the consumer definition but no direct caller or
  literal function-pointer reference in the loaded segment. Callback tables
  or code outside the segment remain possible. The probe retains separate
  firmware addresses for the `0x9fc24000` DMA ring and
  `0x9fc34000` staging/consumer area. With RAM-backed port words A/B, the DMA
  ring contains 192 bytes of A at offset 0 and 192 bytes of B at `0xc0`. The shared
  available count and consumer read offset advance by 192 bytes, and the
  consumer's second module-`0x10` call receives source `0x9fc340c0`; however,
  all 192 bytes there remain the initial `0x11` fill even though the first byte
  at staging base `0x9fc34000` is now B. The probe therefore confirms a
  mismatch in this synthetic function composition. It is consistent with the
  producer reusing `0x9fc34000` as per-packet scratch while its stream ring is
  `0x9fc24000`; it does not establish that firmware invokes this consumer
  after each producer callback or that real USB packets or ROM dispatch behave
  identically.
  A raw-pointer scan of 673 local analysis files (excluding `dec.bin` by
  default) found the exact little-endian value `0xbfc31655` only at the
  operation-table word inside six `usound.drv` files and at the same table
  word in their six embedded `FWIMAGE.FW` copies. Separately including
  `dec.bin` finds one additional copy per corpus: `usound.drv` begins at
  `0x83a00`, so its table word at file offset `0x2a44` appears at `0x86444`.
  These are duplicate copies of the driver's own table, not independent call
  sites. This establishes only that no other exact 32-bit little-endian
  pointer was found in the scanned files; it does not rule out computed
  registration, selector dispatch, or fixed-ROM callers. Reproduce both scans:
  `python3 tools/emu/dac_caller_scan.py analysis --value 0xbfc31655` and
  `python3 tools/emu/dac_caller_scan.py analysis --value 0xbfc31655
  --include-name dec.bin`.
  An earlier PASS report was invalid: the exception shim wrote its validation
  error to `0x80064014`, while the harness checked `0x80060014`, so the second
  packet's byte mismatch was ignored. The probe now checks the correct error
  slot and requires the complete stale-source pattern before reporting this
  observation. The fixed-ROM module-`0x10` destination effect, physical FIFO/DMA,
  PCM/DAC ownership, and audible output remain unverified. Reproduce with
  `bash tools/emu/run_qemu_usound_composition_probe.sh
  <hash-matching-usound.drv>`.
- A hash-deduplicated scan of already-local extracted `.drv`/`.ap` files found
  the same narrow call shape in `udisk.drv`. The checked-in
  `tools/ghidra/run_udisk_setup_probe.sh` now executes the real MIPS16
  `FUN_bfc32abc` in Ghidra p-code (1,163 execution steps) with synthetic MMIO,
  the driver's initialized data/BSS, and zero-return stubs for local module
  break boundaries. It captures ten module-`0x0f` calls and one module-`0x01`
  call. One tuple is `(a0=0x0a,a1=0xbfc30d15,a2=0,a3=0x14)`; the callback
  target is `FUN_bfc30d14` (file offset `0x0f14`), whose decompile reads and
  writes USB MMIO state. The independent execution confirms the call shape,
  but not the fixed-ROM service behavior or callback dispatch. The strongest
  inference remains that operation `0x14` registers a callback keyed by
  `a0=0x0a`; that interpretation is unproven.
- A targeted check of already-local SDK material found no API name for
  module-`0x0f` operation `0x14`; the configured `PD196` SDK source path in
  `docs/discovery-environment.md` is absent from this workspace. The known
  `0x18`–`0x1a` message-queue mappings do not identify `0x14`.
- The setup trace also observes module-`0x0f` operations `0x1d`, `0x1b`,
  `0x09`, and `0x0a`, plus module-`0x01` operation `0x03`. Their semantics
  remain unknown. All module calls return synthetic zero, and MMIO is
  zero-initialized RAM; therefore branches, timing, and any callback invocation
  that depend on real hardware or resident services are outside this probe.
- The scan was limited to already-local extracted `.drv`/`.ap` artifacts and
  the narrow recognizable MIPS16 setup sequence. It can miss computed
  arguments, different register setup, MIPS32 paths, and runtime-loaded banks.
  The distinct `a0=0x14` callback pairing was observed only in `usound.drv` in
  that scan.
- A superficially similar call in `upgrade.ap` is **not** a module-`0x0f`
  analog. `FUN_40054c60` passes `(a0=0x0f, a1=payload, a2=0, a3=6)` through
  the pointer at `0x40055078`, whose value is `0xbfc3b031`. The aligned target
  at `0xbfc3b030` decodes to `li v1,0; break 0`, so this call uses module 0;
  `0x0f` is only its first argument. This comparison does not clarify the
  module-`0x0f` function-pointer service or its operation semantics.
- Static MIPS16 analysis of `FUN_bfc30dc4` and `FUN_bfc312e0` now ties the
  producer to the DMA input: FIFO data is staged at `0x9fc34000`, copied into
  the `0x6e40`-byte ring at `0x9fc24000 + write_offset`, and the DMA address
  register `0xc00c0044` receives the ring base, never the staging address.
  The producer derives transfer length from USB length registers
  `0xc0080008/0xc0080009` and forwards that length to the FIFO/ring path; the
  examined code does not require every packet to equal the descriptor's
  200-byte maximum. The host packet model therefore accepts shorter complete
  audio frames up to that maximum. Actual short-packet delivery by the USB
  controller remains untested.
  `FUN_bfc312e0` writes channel control `0x4b03` and raw size `14112` for the
  direct format, or `0x8b03` and `7056` for format flag 1; both common control
  registers receive `|= 0x404`. The host model records these observed register
  values. DMA size units, hardware wrap behavior, and physical transfer effects
  remain unverified. A QEMU probe now executes `FUN_bfc312e0` in both modes
  against RAM-backed register targets, using the same relocated `0x9fc33e20`
  format-flag address as the producer; each case sets the flag independently, so
  producer-to-DMA mode propagation is not tested end to end. It checks the
  mode-specific control/size values,
  ring address, and common control bits. This proves guest-side register writes,
  not DMA transfers or audible output.
- `tools/emu/run_qemu_usound_chain_probe.sh` composes the two real driver
  control paths in one guest, but it deliberately relocates the distinct
  firmware ring literals (`0x9fc24000`, capacity `0x6e40`; `0x9fc34000`,
  capacity `0x1000`) onto the same QEMU base `0x80061000`. All six local
  corpora pass: producer state reaches 16, its real byte-copy helper writes a
  seeded `0x10..0x1f` pattern into the aliased ring, `FUN_bfc31654` returns 16,
  and the module-`0x10` break-0 call observes the same source address and
  sixteen bytes. A candidate memcpy exception shim now also copies those bytes
  into a sentinel-filled destination and verifies the result. The nine local
  corpora contain the same driver hash; the representative community binary
  passes. This is synthetic aliased-ring data-flow and candidate-ABI coverage,
  not proof that firmware naturally connects these rings or that the stock ROM
  service, PCM owner, or DAC consumes the data on hardware.
- uhost.drv (9216 B): V 6 aligned literals "USBC" (CBW sig 0x43425355) at 0xa08,0xd44,0xed0,0x18bc,0x1984,0x1ad0, no "USBS"; Ghidra also shows SCSI READ(10)/WRITE(10) and 512-byte transfers. It is therefore the Bulk-Only-Transport mass-storage host path (USB flash), not evidence of USB Audio host support. V referenced by config/mainmenu/music/setting .ap.

## Product-documentation cross-check

The available M30 user manual describes the USB port as Micro-USB 2.0 and
documents computer charging/data transfer, while listing the 3.5 mm headphone
and line outputs as the audio outputs. It does not advertise USB Audio or OTG:
[M30 manual](https://manuals.plus/mechen/m30-hifi-player-manual). This is
consumer documentation rather than a board schematic, so it cannot disprove
the hidden `usound` path recovered from the firmware. It does lower confidence
that the retail hardware exposes the required USB device/VBUS wiring; a
config-only experiment should therefore remain unflashed until a reversible
USB-enumeration test is available.
