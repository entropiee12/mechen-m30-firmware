# M30 update, flash, and recovery boundaries

This map separates M30 firmware evidence from the public ATJ2127 host flasher
recipe and the related Actions SDK source. It describes code and captured
command sequences; it is not a hardware procedure. No device, firmware image,
or storage medium was written during this analysis.

## Evidence levels

- **M30 binary:** static/decompiler facts from the extracted `upgrade.ap`,
  `NORFWSC.BIN`, and dated firmware corpus.
- **ATJ2127 reference:** the `actions_flash` source at local commit
  `007efc8` and its README's USB-captured vendor-updater sequence. It is a
  protocol/flow reference, not proof that this exact transaction ran on this
  M30.
- **Actions SDK corroboration:** `Suber/PD196_ATJ2127`'s upgrade C source.
  Its routines illustrate the vendor update design, but do not establish the
  M30 AP's exact implementation.

## M30 SD-card updater path

| Stage | Evidence | Boundary / unknown |
|---|---|---|
| User entry | `setting.ap` `FUN_489146e8` creates confirmation control `0x100a`; the prompt is `setting.sty` text entry `0x85`. `upgrade.ap` points at this entry at file offset `0x13a0`. | The resident UI APIs are outside the AP. The five-option picker is a separate `upgrade.ap` path, `FUN_40014d54`, with controls `0x2001`/`0x2000` and a five-iteration loop. |
| Entry-side operations | `upgrade.ap` `FUN_40014c30` calls indirect slots at `0x40014cc0` / `0x40014cc8` with selectors `0x10`, `0x33`, and `0x4a`; on a negative `0x4a` result it calls selectors `0x11` and `0x34`. | These are observed selector values and arguments, not resolved names. No evidence at this boundary labels them as “confirm”, “erase”, “flash”, “rollback”, or “reboot”. |
| File/decrypt preflight | `FUN_40054c60` references `start decrypt...`, `decrypt init over`, `decrypt file err`, and `search file over`; it requests three metadata records through an indirect call and prepares progress resources. | These strings establish a decrypt/search preflight, not full ordering or successful validation. The exact accepted filename, container checks, checksum policy, and failure cleanup are unresolved. |
| File/config helpers | `FUN_40094c00` and `FUN_40094e3c` use indirect filesystem/config calls. Literal data includes `nor_ud.drv` and `MECHE_M30 HEX`. | Names alone do not prove which member is opened or that the driver is hardware-compatible. The selector/import callees and complete file-validation contract remain unresolved. |
| Firmware/NOR helper | The dated AFI contains `NORHWSC.BIN` at AFI address `0xbfc1e000` (file offset `0x5800`, size `0x800`) and `NORFWSC.BIN` at `0xbfc1e000` (file offset `0x6000`, size `0x2a00`, zero prefix `0x200`). The latter's code starts at candidate `0xbfc1e200`. | AFI address plus code layout match the public recipe's load/execute addresses, but binary identity with its differently named `nandhwsc.bin`/`fwsc.bin` has not been established. `run_nor_fwsc_probe.sh` only exercises the bounded return tail at `0xbfc1e2a8`; it does not run a complete flash operation. |

The reproducible M30 boundary probes are
[`run_upgrade_ap_probe.sh`](../tools/ghidra/run_upgrade_ap_probe.sh) and
[`run_upgrade_ap_corpus_probe.sh`](../tools/ghidra/run_upgrade_ap_corpus_probe.sh).
They inspect the UI/preflight boundary across the dated corpus; they do not
execute a complete updater or write a candidate. The current probe pass does
not resolve the indirect selector services.

### Static selector trace and dispatch boundary

The pointer words at `upgrade.ap` addresses `0x40014cc0` and `0x40014cc8`
resolve to odd MIPS16 pointers `0xbfc3b029` and `0xbfc3b025`. Their resident
stubs load module IDs `0x10` at `0xbfc3b028` and `0x0f` at `0xbfc3b024`,
respectively, then execute `break 0`. In `FUN_40014c30`, the observed call
order is module `0x10`/selector `0x10`, module `0x0f`/selector `0x33`, then
module `0x0f`/selector `0x4a`. The first result is retained as a byte and
passed as `a2` to selector `0x4a`; that call also receives
`(a0=&uStack_20, a1=*param_2, a3=0x4a)`. A negative `0x4a` result takes the
cleanup calls module `0x10`/selector `0x11`, then module `0x0f`/selector
`0x34`, and returns failure. A nonnegative result returns success.

This is a static AP-to-resident-trap trace, not a resolved service
implementation. The kernel exception evidence routes ExcCode 9 through the
vector to fixed-ROM handler `0xbfc06f18`, whose body is unavailable in the
extracted kernel members. No edge currently proves that `FUN_40054c60`'s
decrypt/search preflight result feeds selector `0x4a`; its known indirect call
uses module 0 with selector `6`. A temporary Ghidra p-code probe intended to
exercise the `0x4a` return branch failed before reaching the trap boundary at
`0x40014c6e` on MIPS16 decode/state setup and was removed. **There is no
dynamic confirmation of this selector trace or branch.**

## `actions_flash` host/ADFU transaction reference

The local source is `/Users/berkankarakus/Projects/actions_flash` at commit
`007efc8`. Its README labels the following as the factory updater sequence
captured with USB tracing. `actions_dump.c` shows how the host serializes the
commands, and `payload/adfus.c` shows the command dispatch. Treat the sequence
as a strong ATJ2127 reference, not an M30-specific observation.

| Order | Host command / address | Effect directly supported by source |
|---:|---|---|
| 1 | `simple_switch 0xbfc18000 adfus.bin` | Uploads the ADFU server to RAM and switches the command handler (`actions_dump.c::write_mem`, `adfu_switch`; payload `cmd_vendor`, command `0x20`). |
| 2 | `write_mem 0xbfc1e000 ... nandhwsc.bin`; `exec_ret 0xbfc1e000 0x9c` | Uploads and executes a RAM helper, then attempts to read exactly `0x9c` bytes from its result pointer. This is a hardware-scan stage, not a flash write. |
| 3 | `write_mem 0x9fc24c00 ... nandinfo.bin` | Copies the NAND/flash information blob into RAM for the next stage. The precise structure is not decoded by this command alone. |
| 4 | `write_mem 0xbfc1e000 ... fwsc.bin`; `exec_ret 0xbfc1e200 512` | Uploads and executes the flash helper at the code offset after its `0x200`-byte prefix, then attempts to read exactly 512 bytes from the result pointer. The helper's full behavior is not executed by our existing probe. |
| 5 | `write_flash 0x40000000 ... mbrec.bin`; `0x46000000 ... brec.bin`; `0xc0000000 ... fwim.bin` | Sends three data streams through ADFU flash command `0x10`. The high address byte is passed by `adfus.c::cmd_vendor` as the opaque flash-function `type`; lower 24 bits are the sector address. |
| 6 | `write_mem 0x9fc25400 ... flash_end.bin`; `write_flash 0xff000000 ... flash_end.bin` | Stages a final blob and sends the special high-byte `0xff` flash command. `cmd_flash` calls the opaque ROM function at `0xbfc1e400` with `(0xff,0,0,0)`, then consumes the supplied data without calling that function for each block. The semantic meaning of this final command is not decoded here. |
| 7 | `reset` | Requests device reset through the ADFU server. It is not a rollback operation. |

Relevant local source locations at `007efc8`:
`actions_dump.c:350-358` defines command IDs; `actions_dump.c:611-644`
checks the input file range and 512-byte alignment, sends it in sector units,
and checks the returned USB status; `actions_dump.c:657-683` shows that a fixed
`exec_ret` length is passed directly to READRET (capped at `0x40000`) and is
not compared with `exec_result->size`; `payload/adfus.c:176-197` calls the
fixed ROM flash entry at `0xbfc1e400` in chunks of at most 32 sectors
(`0x4000` bytes); `payload/adfus.c:200-227` routes commands `0x10`, `0x13`,
`0x20`, `0x21`-`0x23`, and `0x60`, with `0x23` sending the caller-requested
length from `exec_result->addr`. The host write path has transport/status
checks, but the captured sequence contains no readback-compare or saved
pre-write image. `flash_fn` is fixed-ROM code and remains opaque, so erase
granularity, write atomicity, and per-region semantics are not established by
this source.

The M30 NORFWSC probe separately observed a candidate result descriptor with
`size=0x58`; that finding does **not** imply that the reference host's
`exec_ret ... 512` request is bounded to `0x58`. The reference host would
request its fixed 512-byte length from the descriptor's address. Whether this
specific ATJ2127 helper/host pairing is safe is not established by the M30
probe and must not be treated as M30 compatibility evidence.

The public C implementation of `_firmware_brec_write` in
[`upgrade_firmware_sub.c`](https://github.com/Suber/PD196_ATJ2127/blob/maint/case/ap/ap_upgrade/upgrade_firmware_sub.c#L1164-L1303)
provides a useful independent design cross-check: it matches the device's
8-byte NAND ID against 128-byte records (skipping a 512-byte table header),
selects a flash type, searches the first 2048 package bytes for a matching
`BREC` entry, writes a 16-KiB initial chunk, then writes the remainder in up
to 32-sector chunks. It injects firmware sector count at BREC offset `+8`
and copies 64 bytes of flash-ID metadata to offset `+128`. This is source code
for the Actions US212A SDK, not proof that the M30's extracted AP does the same
thing. Its shown writer has no paired-backup or rollback transaction.

## Backup and recovery boundaries

- `actions_dump read_brec ... 0 ...` and `... 1 ...` can read two indexed
  boot-record copies; `find_lfi` uses BREC metadata to extract primary and
  backup LFI copies. `fwhelper lfi_repair` can reconcile page errors between
  those firmware copies. These are read/extract/repair capabilities, not a
  proof that the updater writes the two BREC indices transactionally or that
  the boot ROM automatically falls back between them.
- The captured update recipe explicitly writes one `mbrec.bin`, one
  `brec.bin`, and one `fwim.bin` stream. It shows no pre-write dump, backup
  write, post-write readback, or rollback command. Thus the recipe itself
  provides no verified “restore the previous firmware” guarantee.
- The normal SD-card update path requires the player to boot far enough to
  launch Settings/Auto upgrade. ADFU (`10d6:10d6`) is a separate recovery
  interface. The local `dump_bootrom.sh` is dry-run by default; execution
  uploads and runs supplied SRAM code before reading ROM. It deliberately
  does not call `write_flash`, but its no-flash property is not a safety proof
  for arbitrary payloads. An ADFU identity response checks only a chip-family
  guess, not M30 board compatibility.
- The repository's `docs/flash-preflight.md` preserves the stock HEX hash and
  the manual SD update route. That is a rollback source only while the player
  can still enter its updater; no verified M30-specific bootloader recovery
  or NAND restore procedure is presently established.

## Best next offline probe

Next, recover the fixed-ROM implementation reached at `0xbfc06f18` or establish
the missing caller/data-flow edge from `FUN_40054c60` into selector `0x4a`.
For a p-code return-path probe, first resolve the MIPS16 caller's PIC/GP and ISA
state so execution reaches the real resident BREAK boundary. Keep synthetic
service returns clearly labeled; this can validate caller branching but cannot
name the fixed-ROM service without its implementation.

Existing neighboring probes: `tools/ghidra/run_upgrade_ap_probe.sh`,
`tools/ghidra/run_upgrade_ap_corpus_probe.sh`,
`tools/ghidra/run_nor_fwsc_probe.sh`, `tools/ghidra/run_norboot_probe.sh`,
`tools/ghidra/run_kernel_loader_probe.sh`, and
`tools/rom/dump_bootrom.sh` (dry-run by default).
