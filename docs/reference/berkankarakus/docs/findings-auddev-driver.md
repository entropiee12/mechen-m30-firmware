# M30 `aud_dev.drv`: banked audio-device service

`aud_dev.drv` contains a small resident stub, a loaded far-pointer table, and
banked MIPS16 code. Its ROM dispatch boundary and hardware-facing effects are
still only partly understood.

## Reproducible checks

```sh
PYTHONPATH=tools/emu python3 tools/emu/auddev_binary_probe.py \
  analysis/community/fw/aud_dev.drv
```

`tools/ghidra/run_auddev_bank_probe.sh` is retained as an exploratory
disassembly, not as a validated runtime bank-loader probe. The `aud_dev.drv`
A/B unit-table layout and bank-5 file offset are now VERIFIED from the header
bytes and independent bank-5 code checks. The older assumptions in that
script and in `tools/inspect_drv.py` do not establish the device's bank-load
mechanism; see [the unit-table evidence](findings-kernel-op-table.md#driver-bank-tables-verified).

The member is byte-identical in the local community, stock, first-pass,
V1.101.11, V1.101.12, and V5 extractions. SHA-256:

```text
3349287198faaa90a503353673674d5628e85711db9ee16217db5a803484f09c
```

## YQHX segments and the far-pointer table

The header has four segment descriptors. The fourth descriptor at header
offset `0x2c` loads the 21-word table from file `0x200`, length `0x54`, to
`0x9fc09f00`. The resident stub is file `0x300`, length 8, at `0xbfc09f58`.
The other two descriptors are empty; BSS size is zero and the entry field is
`0x1c009000`. `tools/emu/yqhx.py` currently parses all four descriptors and
includes this table in its load plan; `test_yqhx.py` covers the fourth entry.

The resident bytes `10 6b 05 e8 0f 6b 05 e8` are MIPS16 `break 0` stubs for
modules `0x10` and `0x0f`. They are not the COP2-looking words at file `0x200`.
The loaded table begins `25 9b 08 2c 91 9b 08 2c`; entry 18 at file `0x248`
is `0x2c149401`.

The header's little-endian `u16` bank tables use 0x100-byte units: the upper
nibble gives size and the low 12 bits give start. Table B begins at header
`0x100`, with its base at `+0x4c`; entry 5 decodes to file `0x1f00`, size
`0x600` (VERIFIED bytes). Entry 18 of the loaded far-pointer table,
`0x2c149401`, identifies group B, bank 5, and tagged MIPS16 window address
`0x9401` (aligned `0x9400`). The independent `0x2c149958` reference at file
`0x240c` reaches the `spk_comp.dat` name at file `0x2458` with that mapping;
file `0x2500` begins bank 6. This verifies the static file/window mapping,
not the runtime bank-load effect or the fixed-ROM operation dispatcher
(UNKNOWN). `tools/emu/driver_banks.py` parses the distinct `drv_ui.drv`
big-endian start/end layout and is not an `aud_dev.drv` decoder.

## Operation dispatch and EQ shared memory

`mmm_pp.al` calls module `0x0c`, op `0x12` with argument 0 equal to `1` and
argument 1 pointing to `dae_cfg` shared memory. Other calls use argument 0
values `0`, `2`, and `3` (VERIFIED code for that module). MEngine requests
`mmm_mp.al` by name; its EQ command `0x1f` also calls module `0x0c`, op
`0x12`, with argument 0 equal to `1` and the shared pointer as argument 1
(VERIFIED decompile; see [the current chain](findings-kernel-op-table.md#from-config-item-0x96-to-the-dsp-file-name)). The path that loads
`mmm_pp.al` remains UNKNOWN. The table-index-equals-op mapping is an
INFERENCE: the 13 observed calls align with entries, but the fixed-ROM
dispatcher is absent.

Under that candidate mapping, entry 18 points to the MIPS16 bytes at `0x9400`.
The static Ghidra analysis of that verified file range says the `id=1` path checks
`SHM+7`, reads flags and values around `SHM+3..+0xd`, consumes sixteen
8-byte bands from `SHM+0x10..+0x8f`, and reads gain adjustments from
`SHM+0xc0..+0xcb` into a structure at `cfg+0x94`. This is the first evidence
that the EQ shared-memory band area reaches an audio-device routine. Because
the ROM dispatch mapping is not proven, treat the association between op
`0x12` and this entry as an inference.

The same routine is reported to handle `id=0` with a rate structure
`{2, rate*1000, 0x80}`, including a 44000-to-44100 adjustment; entry 19 is
reported as a getter. The producer's EQ builder writes seven bands and clears
bands 7–15, while the consumer reads sixteen. That shape is consistent with
the observed `0xf0`-byte shared-memory structure; it does not show how the
values become audible DSP coefficients.

### Conditional `id=1` groups (direct Ghidra decompile)

A hash-pinned Ghidra decompile of the candidate bank-5 routine at `0x9400` is
retained in
[`auddev-id1-decompile.c`](evidence/auddev-bank5-groups-2026-11-24/auddev-id1-decompile.c).
It shows that `id=1` applies groups only when the corresponding `dae_cfg` flag
is exactly 1:

| Flag | Observed group |
|---|---|
| `+7` | Copy the 16 bands at `+0x10..+0x8f`, applying per-band adjustments from `+0xc2..+0xcb`; otherwise the EQ group is disabled. |
| `+8` | Speaker compensation using `spk_comp.dat` or RAM records, selected by `+0x93`; `+0x94` supplies a length/order field. |
| `+9` | Gain fields `+0x98/+0x9a`, adjusted by `+0xc6` and clamped to `-60..+12`. |
| `+0xa` | Fields `+0x9c/+0x9d`. |
| `+0xb` | Fields `+0x90/+0x92`, adjusted by `+0xca` and clamped to `0..15`. |
| `+0xc` | Three dynamic-range/limiter records beginning at `+0x9e`, with constants 50 and 500 written by the routine. |
| `+0xd` | Output-gain fields `+0xba/+0xbc/+0xbe`; the builder correlates their source with config item `0xa6`. |

The decompile also shows a final rate guard: when the stored rate is above
48 kHz, it clears consumer-state fields at byte offsets `+0x40`, `+0x3a`, and
`+0x28`. This is code-level evidence of state clearing, not proof that every
related audio group is disabled. The `+7` source is config item `0x85`, whose default is zero;
the MEngine builder sets `+7=1` when it submits an EQ structure. The builder
also sets `+0xd` when the selector is nonzero. Consequently, Off and a
zero-valued Custom curve need not produce identical input structures: the
output-gain group is enabled for Custom and disabled for Off. Its audible
effect and physical units remain unknown.

The DRC gate is `dae_cfg+0x0c`: Ghidra renders its source as `param_2 + 3`,
where `param_2` is an `int *`, so the expression means byte offset `0x0c`.
The p-code probe enables flags `+7,+9,+0xa,+0xb,+0xc,+0xd` in a synthetic
structure and checks the resulting band, gain, DRC-record, and output-gain
state fields. A second case keeps the same other fields but clears `+0xc`,
verifying that the DRC flag and its records/constants remain unwritten. The
three DRC record copies use the same candidate `memcpy` shim as the 16 EQ band
copies. The reproducible command and exact scope are in
[`group-probe evidence`](evidence/auddev-bank5-groups-2026-11-24/README.md).

The runner checks mapped code bytes against file offset `0x1f00` in the
hash-pinned driver. The header table independently verifies that file offset;
the runner does not validate the runtime bank loader or ROM dispatch mapping.
Association with module `0x0c` operation `0x12`, the caller-supplied
structure base, the untested `+8` file/RAM path, and any audible DSP or hardware
effect remain separate evidence boundaries.

The reported follow-up also says the `0xf0`-byte structure is persistent
record ID `0xf0000` with magic `0x55b9`, and that `config.ap` and `mengine.ap`
run their config-item initializer only after an invalid/missing record. The
MEngine initializer's field writes are independently reproduced in
[`initializer evidence`](evidence/auddev-dae-gates-2026-11-24/README.md), but
its persistent read/write wrapper was not recovered in that run. If the
reported wrapper is correct, config.bin supplies factory values only on
first initialization or record reset; a valid record can retain older values.
The zero defaults for `+8..+0xb` therefore describe a cleared/factory block,
not every persisted unit.

`tools/ghidra/run_auddev_eq_copy_probe.sh` executes the candidate bank-5 entry
at `0x9400` with either a synthetic payload or an optional `0xf0`-byte EQ
structure. It pins the driver SHA-256 and the Ghidra script checks that the
mapped code bytes match file offset `0x1f00` before execution. Real MIPS16 instructions make 16
calls through the slot at `0x98fc`, whose image value is `0xbfc09f59`, the odd
pointer to the resident module-`0x10` `break 0` stub. The captured call shape is
`a0=0x9fc34094+8*i`, `a1=source+0x10+8*i`, `a2=8`, `a3=0` for `i=0..15`;
the run took 907 p-code steps. The probe now services each call with an
explicit candidate `memcpy(a0,a1,a2)` shim and verifies all 16 destination
bands byte-for-byte against their source. The MIPS16 caller and its addresses
are real; the copy operation is a hypothesis from the shared call shape, not
the unavailable fixed-ROM implementation. This closes the caller's local
data-layout check under that shim, but does not prove ROM dispatch, DSP
coefficient use, or audible output.

`tools/ghidra/run_eq_auddev_chain_probe.sh` runs the real `mengine.ap` builder
for Off, Hip-hop, and default Custom, then feeds the resulting `0xf0`-byte
buffers to the candidate `aud_dev` consumer. The harness initializes the
builder buffer with `0xaa`, so the seven active band records and zeroed
inactive band region are produced by the builder, while trailing adjustment
bytes are deliberately preserved sentinels, not firmware defaults. For each
preset, the chain passes three tail cases: preserved sentinels, a synthetic
zeroed adjustment area, and synthetic low/high threshold-and-gain settings.
The candidate MIPS16 code's observed gain adjustments match those settings
band by band, and all 16 destination records are checked under the same
synthetic module-`0x10` memcpy shim. This verifies the candidate consumer's
record layout and adjustment arithmetic for these inputs; it does not prove
ROM dispatch, the actual service implementation, runtime tail values, DSP
consumption, or audible difference.

The banked code also references `spk_comp.dat`, a speaker-compensation filter
asset. Its relation to EQ gain processing and the downstream coefficient or
DSP path remains unknown. `drv_dac.drv` register writes and the fixed-ROM
operation dispatcher are the next static targets.

## Emulator boundary

The host YQHX parser now records four segment descriptors, including this
table segment. The aud_dev binary probe checks both nonempty segments and the
far-table word. This models the file's declared load layout; it does not
execute the banked code, emulate the ROM dispatcher, or model audio hardware.

`tools/sim/auddev_driver.py` also models the observed MMIO read/modify/write
effects for candidate operations 4, 6, and 8. The formulas come from the
banked MIPS16 routines and their literal pools; the model requires callers to
provide each input register word and returns the observed zero result. It
does not claim that the missing ROM dispatcher selects these routines with
`table[op]`.

Operation 6 writes the low nibble of `0xc0000110` to 6, or to 2 for codes
`0x0b`, `0x16`, and `0x2c`. It selects the low three bits of
`0xc000100c` from the observed input-code table. When bit 0 of `0xc0052000`
is set, the selector is adjusted by the modeled branches; interpreting the
input as a sample-rate code is an inference. The firmware stores the
`0xc000100c` selector only when its computed value differs from the current
value; the host model preserves that conditional store.

Operation 4 updates `0xc0000000`, `0xc0050000`, `0xc005001c`, and
`0xc0050004`. Operation 8 updates either `0xc0051010` or, for argument 0
equal to 4, `0xc0090060` and `0xc0051000`. The model keeps these operations
numbered because their higher-level meanings have not been established.
Seventeen host tests cover the formulas, branch cases, conditional stores,
input validation, and
no-partial-write behavior; they validate the model implementation, not the
firmware-to-ROM dispatch boundary or physical register effects.

Remaining unknowns include whether the ROM dispatcher indexes the table
directly by op, how the runtime bank loader uses the verified static table,
the exact behavior of non-EQ operations, `spk_comp.dat`
consumption, and the coefficient/DSP output path. No firmware patch or device
write has been made.
