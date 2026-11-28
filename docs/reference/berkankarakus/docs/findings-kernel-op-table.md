# Kernel operation table, shared-memory registry and the config-to-DSP flow

Read-only static analysis and Ghidra p-code, 2026-11-27. Inputs (SHA-256 checked
by the tests and probes named below):

| Member | SHA-256 |
| --- | --- |
| `kernel.drv` | `58f9d0c62bdbdab7cfeee1c5e65bbe8b88ee962f8eecd3dd0515d1a1056d8bd6` |
| `aud_dev.drv` | `3349287198faaa90a503353673674d5628e85711db9ee16217db5a803484f09c` |
| `config.ap` | `5b5687c00880e691500c7abbfba43dab51ae0f95d2203f74aced2e8d56357580` |
| `config.bin` | `900f814711f50446802ee36bcc2d1560df55b9d2fb4e2d964bb35004517b6d19` |
| `mmm_pp.al` | `9a7a6c2215b2594e2ef816b8a3e592968921afff7747f3cadbd6e71bf74047d6` |

The PD196 SDK (`Suber/PD196_ATJ2127`) is used only as a labeled hint for
operation names. It is not M30 evidence. Decompiles and the full 77-entry table
are in [`evidence/kernel-op-table-2026-11-27`](evidence/kernel-op-table-2026-11-27/README.md).

## Driver bank tables (VERIFIED)

`yqhx` drivers carry two bank tables of little-endian `u16` entries. The upper
nibble is the bank size and the low 12 bits the start, both in `0x100`-byte
units. Table A is at header `0x80` (base: header `0x48`, window `0x9000`),
table B at header `0x100` (base: header `0x4c`, window `0x9400`). Evidence:

- `aud_dev.drv` table B entry 5 decodes to file `0x1f00`, size `0x600`, the
  bank the `RunAuddevIdx18*` probes execute.
- In `kernel.drv` both tables have nine contiguous entries and the last bank
  ends exactly at the file end (`0x4c00`).

Far pointers carry the group in the high nibble of the top byte (1 is A, 2 is
B), the bank in `byte2 / 4` and the window address in the low 16 bits (bit 0
is the MIPS16 tag). Implemented in `tools/sim/driver_bank_table.py`.

The `drv_ui.drv` parser in `tools/emu/driver_banks.py` uses big-endian four-byte
`(start, end)` pairs. The two decoders must remain scoped to their respective
evidence. On the real `drv_ui.drv` (SHA-256
`011f4e6a50738026ac641370f493c5df2e59adc435871d8a213e717604d2576a`),
the observed far pointer `0x26089471` resolves to file `0x8af1` with the
existing UI parser (aligned entry `0x8af0`, nonzero MIPS16 bytes). Applying the
kernel/aud_dev unit-table rule directly would resolve bank B2 to file `0x7a71`
(aligned `0x7a70`, all zero bytes). The known UI B3 pool at file `0x992c..0x996c`
also falls in B3 under the existing parser, versus B6 under direct unit-table
indexing. This is VERIFIED for these file bytes and pointer positions; the
hardware bank loader is still UNKNOWN. No parser was replaced on this evidence.

## Slot 0x0f operation table (VERIFIED bytes, dispatcher link INFERENCE)

The resident segment of `kernel.drv` (file `0x200`, VA `0xbfc08000`) starts
with 77 word entries (`table[op]`, 0-based); entry `0x3e` is zero. The
exception vector at `0xbfc08180` follows the table.

| Class | Count |
| --- | --- |
| ROM pointers below `0xbfc08000` (bodies not in the corpus) | 29 |
| Bank far pointers (decodable from `kernel.drv`) | 34 |
| Resident `kernel.drv` code | 11 |
| Outside every corpus segment (`0xbfc19bad`, `0xbfc19cbd`) | 2 |
| Hole | 1 |

The order is consistent with the PD196 `kernel_cmd_e` enum (67 names) for the
sampled entries: `EXECE_AP`, `LOAD_CODEC` and the next loader are neighbors in
bank B0, `MALLOC`/`FREE` are `0xbfc07bf9`/`0xbfc07c75`, `MQ_SEND`..`MQ_FLUSH` are
consecutive ROM functions, `OS_SCHED_LOCK/UNLOCK` are operations `0x33/0x34`.
It is a hint, not an identity: operation 3 is named `FREE_AP` in the SDK but
the M30 function at that index loads an image and frees nothing (see below).
Entries `0x43..0x4c` have no SDK name. That the ROM dispatcher at `0xbfc06f18`
indexes this table with `a3` is an inference from this match and from the call
shapes; the dispatcher body is not in the corpus. Slot `0x10` (libc) and `0x11`
(vfs) have no provider in the 31 `yqhx` members (header byte 1 scan), so their
tables and the copy/fill service stay synthetic.

`VM_READ` (`0x12`, `0xbfc19bad`) and `VM_WRITE` (`0x13`, `0xbfc19cbd`) are not
inside any member segment, and no image supplies their bytes: a search of every
`fw/` member, the AFI members (`NORBREC`, `NORMBREC`, `NORFWSC`, `NORHWSC`,
`NOR_ID`, `CARDPRDU`, `FWIMAGE.FW`) and `dec.bin` for the handler pointers
found none of the code. The exception constructor in `ker_init.bin` builds
`0xbfc19624/0xbfc195d4/0xbfc19a75` with `lui/addiu`, so no literal pool holds
them either. `NORBREC.BIN` at base `0xbfc18000` does not fit (its code at the
corresponding offsets is mid-function), and its own base is about `0xbfc32000`;
its table of `KER_*.BIN` names points at the four `ker_*.bin` members and a
buffer `0xbfc19580`. The region holds code (the VM functions, handlers) and a
table the kernel writes (`0xbfc19934`), so whether it is boot ROM, a mask-ROM
image or RAM filled by an unidentified loader is UNKNOWN. The persistent read
and write path is therefore not decodable from the corpus. A memory dump of
`0xbfc19000..0xbfc1a000` and `0xbfc00000..0xbfc08000` from a device (ADFU or a
hardware stub) would settle it; the caller side (`helper(buf, id, len, op)` with
ids such as `0xf0000`) is the only ABI evidence available.

Machine-readable form: `tools/sim/kernel_ops.py` (`parse_kernel_op_table`).

## Operations 0..3: the AP/codec/image loaders (VERIFIED decompile, ROM helpers INFERENCE)

Bank B0 of `kernel.drv`, read with the repo's `MapSegments.java`/`DumpAt.java`:

- Operation 0 (`0x9408`, `EXECE_AP` in the SDK): `(name, slot, param)`. It takes
  a lock (`0xbfc05125`), opens the file by name, reads a `0x4c`-byte header,
  compares the word at `+4` with a ROM literal (value unknown; the AP files
  carry `yqhx` there), then for the four 12-byte records `{file offset, length,
  destination}` at `+0x08`, `+0x14`, `+0x20`, `+0x2c` seeks and reads each
  nonzero section straight to its destination, clears BSS (length `+0x38`,
  address `+0x3c`), stores the file start in a 12-byte-stride table, closes the
  file and calls the entry at `+0x40` as `entry(param, copy of 8 bytes from
  +0x44)`. Return value 0 means success, anything else gives `-1`. The AP's
  own banks are not loaded here (UNKNOWN who loads them). This is the real
  `yqhx` loader; the earlier `FUN_bfc0a408` reading in
  `findings-kernel-loader.md` was already withdrawn.
- Operations 1 and 2 (`0x9578`, `0x96a0`): same open/read pipeline for a
  `0x44`-byte header, no entry call; they fill the other fields of the same
  12-byte-stride table (offsets `+8` and `+4`) and clear interrupt-slot words
  (`0xc00a00xx`). They fit codec and mmm-library loaders. Operation 2 also
  special-cases the name `stub.al` (bytes at `0x9400`).
- Operation 3 (`0x9808`): loads a five-section image, stores a handle in a
  separate four-byte-stride table and clears interrupt slots and two 0x20-byte
  buffers. It frees nothing, so the SDK name `FREE_AP` does not hold. Which
  M30 operation unloads an AP is UNKNOWN.
- All four open files through a flash filesystem routine in bank A2
  (`0x1f089001`, file `0x1200`): the name is converted to upper-case 8.3,
  `0x20`-byte directory entries from offset `0x200` are compared, a descriptor
  from an eight-entry table at `0x9fc1a8c8` is returned (`+4` = start offset
  times `0x200`), and failures print `<S_ERR> sd_open fail`.
- Helpers `0xbfc05125/0xbfc05369` (lock/unlock), `0xbfc043ad/0xbfc04419/
  0xbfc04391` (seek/read/close), `0xbfc04635` (copy), `0xbfc045f5` (fill) are
  ROM; their names are inferred from call shapes.
- How the manager message `{0x201, appId, param}` becomes `(name, slot, param)`
  is not shown by this code (UNKNOWN). None of these operations touches shared
  memory id 3.

## Operations 0x47, 0x48, 0x49 and 0x4a, 0x4b

Bank A6 of `kernel.drv`, decompiled and executed with
`tools/ghidra/run_kernel_shm_ops_probe.sh` (IRQ locks synthetic):

- `0x47` create (`a0` id, `a1` pointer, `a2` length): stores the pointer in an
  eight-slot table at `0x13d58` without copying data; with no free slot it calls
  `0x4b` and returns `-1`.
- `0x49` attach (`a0` id): returns the stored pointer, or 0.
- `0x48` delete (`a0` id): frees the slot.
- A second create with the same id is not checked: it takes another slot and
  attach returns the first match (decompile only, not executed).
- `0x4b` is a guarded debug print (`flag & 2` then `flag |= 4`, call, clear),
  not a panic.
- `0x4a` (bank B0 `0x9944`) is a task create: it records `{entry, argument,
  stack top, u16 size}`, fills the stack with `0x5a`, stores `{addr & 0x3ffff,
  size}` in an eight-slot table and calls ROM `0xbfc069e1`. `mmm_bp`/`mmm_pp`
  call it with `{0xbfc27735, context, buffer, 0x4b0}` (INFERENCE from decompile).
  The ROM call receives only `{entry, argument, stack top}`, the id byte and the
  flag byte; the size is not passed. A second table at `0xbfc19934 + 0x40` holds
  `(stack base + 0xc0) & 0x3ffff` per id (a guard limit, INFERENCE). The id
  lookup `0xbfc196d0` is outside every corpus segment (UNKNOWN).

Consequence (VERIFIED in kernel code, dispatcher link INFERENCE): `config.ap`
`FUN_40dd5034` creates id 3; both `mmm_mp.al` and `mmm_pp.al`
`FUN_68012d00` attach id 3 and store the same pointer at context `+0x9c`.
The music/MEngine path requests `mmm_mp.al`; the loader path for `mmm_pp.al`
is UNKNOWN. Only `config.ap` references
`FUN_40dd4fe8` and so creates the segment; `music.ap`, `mainmenu.ap`,
`playlist.ap`, `setting.ap` and `browser.ap` contain the code but no pointer to
it (static pointer scan; computed calls are not excluded).

## From config item 0x96 to the DSP file name

Models: `tools/sim/dae_shm_flow.py`, tests `tools/sim/test_dae_shm_flow.py`.

1. `config.ap` `FUN_40dd4c00`: item `0x96` to `state+0x18`; `FUN_40dd4fe8`: `+2/+3`
   from `state+0x18 == 1`; `FUN_40dd5034`: register 0xf0 bytes as id 3 (p-code,
   `run_config_item96_shm_probe.sh`).
2. Persisted blocks (VERIFIED decompile and pool words): `config.ap`
   `FUN_40054f38`, called at the end of `FUN_40054f84`, writes the RAM settings
   block (`0x9fc15654`, id 0, `0x38` bytes) and the RAM DAE image (`0x9fc15700`,
   id `0xf0000`, `0xf0` bytes) to flash, after `FUN_40dd4fe8` already patched
   `+2/+3`. `mengine.ap` init `FUN_60050d00` reads the same ids back: the
   settings block to `0x9fc15d9c` and the DAE block to its local structure
   `0x9fc15dd4`. Only `config.ap` and `mengine.ap` reference id `0xf0000` among
   the `.ap` members (plus the card and kernel drivers' unrelated words). The
   ROM flash read/write services are assumed to return what was written.
3. `mengine.ap` builder `FUN_601d0d58` copies the local structure to the pointer
   from kernel op `0x49` (id 3) and then sends mmm_mp command `0x1f` (module
   `0x0c` op `0x12`, `a0=1`, the shared pointer), which feeds `aud_dev`. It runs
   from the `0x303` handler `FUN_601d0f30` and from `_play` (`FUN_60190eec`: the
   builder pointer literal `0x601d0d59` sits at `0x60191084`), directly before
   `AOUT_OPEN` and `PLAY`. Its gate is byte `+0x18` of the settings block copy
   at `0x9fc15d9c` (`0x9fc15db4`), the byte `config.ap` seeds from item `0x96`
   (same persisted byte, default 1), and local `+0x0c != 1`. It is not the
   `eq_type` payload byte (which lands at `0x9fc15cc8/cc9` in MEngine's
   `0xdc`-byte block `0x9fc15cb0`, id `0xa0000`); an earlier note that Off skips
   the builder was wrong. The builder also mirrors the selector into
   `+0x19` of the settings copy.
3. `music.ap` `FUN_40094dd4(file_info, mode)` (called by `FUN_40095130` from
   `FUN_40054ff4`, which `FUN_400552a0` calls): read the byte at `0x9fc1a1c8`
   through `FUN_40094d10` (0, 1 and 2 are tested; 2 sends message `0x204` first);
   if it is not 1, send the stored EQ selector (`FUN_40094d4c`, block `+0x19`)
   and the volume (`FUN_40094d28`); send the play-mode byte (`FUN_40614f30`);
   only then set the file and start playback. Selector default (`config.bin`
   item `0x97`) is `[0, 0, 7, 1]`, Off. `mainmenu.ap` sends `0x303` only from
   its EQ screens. That byte `1` means "engine already playing" is an
   inference; its writer was not traced.
4. `mmm_mp.al` (reached from MEngine through the slot-`0x42` stub) command
   dispatcher `FUN_bfc13864` forwards to `FUN_68092d00`; commands used by
   MEngine's `_play`: `0` open (`FUN_68052d00`, attaches id 3 with op `0x49`),
   `0x11` set file, `0x15`/`0x20` mode, `0x19` PLAY (`FUN_68012da0` which calls
   `FUN_68312d00`, the DSP start), `0x1a` stop, `0x1e` volume, `0x1f` set EQ.
   Command numbers are VERIFIED code; names are SDK hints only.
5. DSP start `FUN_68312d00` (bank file `0x5700`, literals decoded): shared `+2 ==
   0` opens `adppext.dsp`; nonzero opens `adppdrc.dsp` when the context sample
   rate is below `0xbb81` (at most 48000 Hz) and `audiopp.dsp` above it. The
   decoder DSP is opened first by index (`ctx+0x80`).
6. `mmm_pp.al` holds a simpler variant (command 8 to `FUN_68013258`: nonzero
   `+2` gives `audiopp.dsp` at every rate). Both `mmm_pp.al` and `mmm_mp.al`
   are present in the extracted stock and community trees and share the
   resident base `0xbfc13500`; which feature loads `mmm_pp.al` is UNKNOWN.
   MEngine requests `mmm_mp.al` by name (VERIFIED caller/literal, successful
   load INFERENCE). See [`findings-mmm-module-selection.md`](findings-mmm-module-selection.md).
   The earlier `mmm_pp` probe results stay valid for that module only.

Order in `_play` (VERIFIED code): builder copy, mmm_mp command `0x1f`, `AOUT_OPEN`,
`PLAY` (DSP selection). The copy therefore precedes the DSP choice whenever the
gate is open.

P-code check of the whole chain (`tools/ghidra/run_config_to_mengine_dae_chain_probe.sh`,
synthetic: config item getter and reader, fill/copy stubs, flash as files, command
stub, shared buffer; real: `FUN_40dd4c00`, `FUN_40dd4d60`, `FUN_40dd4fe8`,
`FUN_40054f38`, `FUN_60050d00`, `FUN_601d0d58`). Item `0x96` = 0, 1, 2:

| item `0x96` | saved `dae+2/+3` | MEngine local `+2/+3` | builder body | shared `+2/+3` | DSP at or below 48 kHz / above |
| --- | --- | --- | --- | --- | --- |
| 0 | 0/1 | 0/1 | skipped (`+0x18 == 0`) | 0/1 | `adppext.dsp` / `adppext.dsp` |
| 1 | 1/0 | 1/0 | runs, command `0x1f` sent | 1/0 | `adppdrc.dsp` / `audiopp.dsp` |
| 2 | 0/1 | 0/1 | runs, command `0x1f` sent | 0/1 | `adppext.dsp` / `adppext.dsp` |

The selector (Off or Rock) changes only `+7` and `+0xd`, never `+2/+3`; forcing
local `+0x0c = 1` skips the body. The DSP column applies the decoded
`mmm_mp.al` rule to the shared byte (that function was not run). `FUN_40dd4d60`
does not write the `0x55b9` DAE magic; the probe sets it, standing in for
`FUN_40054ccc`.

Result of the model: with the config default (item `0x96` = 1) the persisted
`+0x18` byte is 1, so the builder gate is open; MEngine's local structure is the
DAE image config saved, whose `+2` is 1, so the builder copy keeps shared `+2 =
1`. A 44.1 kHz play opens `adppdrc.dsp` and a 96 kHz play opens `audiopp.dsp`
(`+2 = 0`, for example when item `0x96` was 0 at the first initialization, opens
`adppext.dsp`). The shared `+2` could only become 0 if the flash blocks were
rewritten by something else. This reverses the first model, which assumed a
zero local structure; that came from the BSS range, but `FUN_60050d00` overwrites
the structure from flash before use (`mengine.ap`'s BSS still covers
`0x9fc15dd4`, header `0x9fc15c08` plus `0x3e4`).

Not established (INFERENCE overall): that no other code rewrites the flash
blocks between `config.ap`'s save and MEngine's read, that the ROM
`VM_READ`/`VM_WRITE` round-trip preserves the image, the identity of
`FUN_60191080(1, 3, ptr)` in `_play` (target outside the mapped banks), and the
meaning of the `0x9fc1a1c8` byte. Device observation or a p-code run of both
init paths is needed. No claim about the audible effect of any DSP.

## Media-library slots 0x40..0x43 (INFERENCE, halfword scan of the stubs)

A scan of all `yqhx` members for `li v1, SLOT; break` stub halfwords (`6bNN e805`)
finds slot `0x42` only in `mengine.ap`, slot `0x43` only in `mmm_mp.al`, and slot
`0x40` in `manager.ap`, `music.ap`, `udisk.ap` and `usound.ap`. The PD196
`api.a` names these slots `mmm_mp_cmd` (`0x42`), `audio_decoder_cmd` (`0x43`)
and the `mmm_id/mr/vp` command family (`0x40`). So `mengine.ap` calls slot
`0x42`, whose body is probably `mmm_mp.al`, and `mmm_mp.al` calls slot `0x43` (the
decoder side). No file has a header slot byte of `0x40..0x43`: the `.al`
members are registered by the loaders in operations 1 and 2, not by a slot
header. The mapping is not proven.

## aud_dev table 18 internal state

`tools/sim/auddev_dae_update.py` (tests `test_auddev_dae_update.py`) encodes the
six-run probe matrix: `cfg+0x3a` is 1 only at or below 48000 Hz, `cfg+0x28` is 1
at or below 48000 Hz, `cfg+0x40` is copied from `dae+0x0c` and cleared above
48000 Hz, and the 16 bands are copied at every rate. Paths that call ROM
services or adjust gains raise `NotImplementedError`.

## Reproduction

```sh
PYTHONPATH=tools/sim python3 -m unittest test_driver_bank_table test_kernel_ops \
  test_dae_shm_flow test_auddev_dae_update
bash tools/ghidra/run_kernel_shm_ops_probe.sh
bash tools/ghidra/run_mmm_pp_dsp_select_probe.sh
bash tools/ghidra/run_config_item96_shm_probe.sh
bash tools/ghidra/run_config_to_mengine_dae_chain_probe.sh
```
