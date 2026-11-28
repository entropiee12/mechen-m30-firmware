# ROM dependency map

This document freezes the remaining ROM boundaries after the `kernel.drv`
operation-table and exception-dispatch work. It does not treat the PD196 SDK
as M30 evidence. SDK operation names below are labels that fit the table order;
the M30 pointers and table indices are the firmware evidence.

A previous M30 ADFU attempt did not produce a ROM dump. That is context for
why these boundaries remain open. This document does not propose another
device operation, payload, firmware change, or patch.

Status vocabulary:

- **VERIFIED**: established from extracted M30 bytes, decompile, or a bounded
  probe that executed those bytes.
- **INFERENCE**: supported by call shape, data flow, or the PD196 SDK ordering,
  but not by the missing target body.
- **UNKNOWN**: the target bytes or their effects are unavailable.

The useful artifact would be one coherent dump containing the live bytes for
`0xbfc00000..0xbfc1ffff` (or a larger range that includes it). A dump from a
different boot mode would still need its mapping checked before being applied
to normal M30 execution.

## Summary

| Dependency | Current status | What one matching dump would settle |
| --- | --- | --- |
| Exception/syscall target `0xbfc06f18` | **VERIFIED** as the ExcCode 9 destination installed by `ker_init.bin` and reached by the real `kernel.drv` vector. **INFERENCE** that the same body dispatches slot `0x0f` operations through `table[a3]`. Handler body **UNKNOWN**. | Recover the handler instructions and show whether and how it validates the module slot, indexes the operation table, preserves registers, and returns from the exception. |
| 29 `kernel.drv` operation-table pointers below `0xbfc08000` | Pointer values and ROM classification **VERIFIED**. The PD196 names are **INFERENCE** for M30. Bodies **UNKNOWN**. | Supply all 29 bodies in one image, allowing each SDK label to be accepted, corrected, or rejected from real M30 code and replacing synthetic emulator responses where the ABI is measurable. |
| Copy/fill helpers `0xbfc04635` / `0xbfc045f5` | Call sites, argument triples, return boundaries, and downstream data dependence **VERIFIED**. `memcpy`/`memset` semantics are **INFERENCE**; bodies **UNKNOWN**. | Establish direction, overlap behavior, return value, edge cases, and side effects, so host probes no longer need synthetic copy/fill implementations. |
| Scheduler helpers `0xbfc05125` / `0xbfc05369` | Operation-table addresses and balanced caller placement **VERIFIED**. `OS_SCHED_LOCK` / `OS_SCHED_UNLOCK` names are **INFERENCE** supported by PD196 ordering. Bodies **UNKNOWN**. | Show the scheduler state manipulated, nesting/return ABI, and whether callers need additional interrupt or memory-order effects. |
| File helpers | Banked open routine `0x1f089001` is **VERIFIED** extracted code. ROM entries `0xbfc04391` (close), `0xbfc043ad` (seek), `0xbfc04415` (tell), and `0xbfc04419` (read) are **VERIFIED** table targets; names/effects are **INFERENCE**. | Confirm the ROM descriptor ABI, seek origin and units, read return/error convention, and close behavior. The dump is not needed to recover the already extracted open routine, but it closes the ROM half of its pipeline. |
| Task helper `0xbfc069e1` | `kernel.drv` op `0x4a` constructing a task record and calling this address is **VERIFIED** decompile. Actual task creation and scheduling effects are **UNKNOWN**; the task-create interpretation is **INFERENCE**. | Reveal the accepted record fields, id/priority handling, stack initialization contract, return/error behavior, and whether execution can begin before the caller returns. |
| `VM_READ` / `VM_WRITE` at `0xbfc19bad` / `0xbfc19cbd` | Table pointers and caller-side `(buffer, id, length, op)` shapes **VERIFIED**. The names are **INFERENCE** from PD196 order and persistent-record behavior. No extracted image supplies these bytes; backing region and loader are **UNKNOWN**. | If the live dump contains this region, recover persistent-storage addressing, record lookup, copy direction, status codes, atomicity, and the actual relation between ids such as `0xf0000` and storage. It would also establish whether the region is ROM-backed or populated RAM in that mode. |
| Exception handlers `0xbfc19624`, `0xbfc195d4`, `0xbfc19a75` | Constructor-produced table values and vector routing for ExcCode 0–13 are **VERIFIED** in p-code. Handler semantics and normal-device invocation are **UNKNOWN**. | Recover the three handlers and distinguish reset/fault/default behavior, saved-frame use, recovery/return policy, and dependencies on MMIO or other unavailable routines. |
| AP header comparison source `0xbfc04140` | The extracted loader's four-byte comparison against a ROM-resident source is **VERIFIED**; the source value and its status as a constant versus computed/aliased data are **UNKNOWN**. The observed AP files carry `yqhx` at header `+4`. | Read the four bytes directly, confirm the accepted magic and byte order, and remove the synthetic/assumed header check from loader models. |

## The 29 fixed-ROM operation-table entries

The following pointers come directly from `kernel.drv` file offset `0x200`.
Every address and index is **VERIFIED**. Every name is an **INFERENCE** from
the matching position in the PD196 `kernel_cmd_e` enum unless separately
constrained by M30 call shapes.

| Op | ROM target | SDK-order label |
| ---: | ---: | --- |
| `0x09` | `0xbfc079fd` | `DRV_UNINSTALL` |
| `0x0e` | `0xbfc04391` | `SD_FCLOSE` |
| `0x0f` | `0xbfc043ad` | `SD_FSEEK` |
| `0x10` | `0xbfc04415` | `SD_FTELL` |
| `0x11` | `0xbfc04419` | `SD_FREAD` |
| `0x16` | `0xbfc07bf9` | `MALLOC` |
| `0x17` | `0xbfc07c75` | `FREE` |
| `0x18` | `0xbfc04ce9` | `MQ_SEND` |
| `0x19` | `0xbfc04d65` | `MQ_RECEIVE` |
| `0x1a` | `0xbfc04de5` | `MQ_FLUSH` |
| `0x1d` | `0xbfc04fb9` | `MDELAY` |
| `0x1e` | `0xbfc04f61` | `UDELAY` |
| `0x1f` | `0xbfc04ee5` | `SLEEP` |
| `0x20` | `0xbfc04f19` | `USLEEP` |
| `0x23` | `0xbfc07a71` | `SET_TIME` |
| `0x24` | `0xbfc07abd` | `GET_TIME` |
| `0x25` | `0xbfc07ae9` | `SET_DATE` |
| `0x26` | `0xbfc07b45` | `GET_DATE` |
| `0x28` | `0xbfc07bcd` | `GET_ALARM_TIME` |
| `0x2b` | `0xbfc06515` | `GET_FAT_TYPE_AFTER_MOUNT` |
| `0x2d` | `0xbfc064c8` | `LOCAL_IRQ_SAVE` |
| `0x2e` | `0xbfc064d4` | `LOCAL_IRQ_RESTORE` |
| `0x2f` | `0xbfc05925` | `OS_TASK_RESUME` |
| `0x30` | `0xbfc059b9` | `OS_TASK_SUSPEND` |
| `0x31` | `0xbfc05ae5` | `OS_TIME_DLY` |
| `0x32` | `0xbfc05b3d` | `OS_TIME_DLY_RESUME` |
| `0x33` | `0xbfc05125` | `OS_SCHED_LOCK` |
| `0x34` | `0xbfc05369` | `OS_SCHED_UNLOCK` |
| `0x35` | `0xbfc04ed5` | `RANDOM` |

This list deliberately excludes `VM_READ` and `VM_WRITE`: their targets are
above the resident `kernel.drv` segment rather than in the 29-entry fixed-ROM
class, and no extracted member supplies their code.

## Evidence and limits

- [`findings-kernel-op-table.md`](findings-kernel-op-table.md) records the
  77-entry table classification, loader decompiles, ROM helper call shapes,
  task record, and the negative corpus search for `VM_READ`/`VM_WRITE`.
- [`evidence/kernel-op-table-2026-11-27/kernel-op-table.txt`](evidence/kernel-op-table-2026-11-27/kernel-op-table.txt)
  is the complete address/index inventory. Its SDK names are reference hints,
  not M30 identities.
- [`findings-kernel-exception-dispatch-matrix.md`](findings-kernel-exception-dispatch-matrix.md)
  records the constructor-to-vector routing matrix. The probes stop at each
  selected handler and do not execute the missing bodies.
- [`findings-kernel-loader.md`](findings-kernel-loader.md) records the real
  caller arguments and return boundaries for the copy/fill candidates and
  keeps their effects synthetic.
- [`findings-rom-dump-availability.md`](findings-rom-dump-availability.md)
  explains why public ADFU source is only an acquisition lead. Source-level
  copy-loop tests do not establish an M30 ROM mapping or produce a dump.

One dump would answer code and constant questions at these addresses. It would
not by itself prove that normal boot uses the same physical alias, that the
dumped mode invokes each path, or that hardware/MMIO effects match a host
model. Those remain separate runtime questions.
