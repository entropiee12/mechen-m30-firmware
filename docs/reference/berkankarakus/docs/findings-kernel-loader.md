# Kernel driver loader and UI bank addresses

This note records the first resident/kernel-side evidence for the `yqhx`
driver format. It is based on the extracted community image and cross-checked
against the archived stock images. It does not claim a complete ATJ2127 boot
emulation.

## Stable image evidence

`kernel.drv` and `drv_ui.drv` are byte-identical in all six locally archived
images (`stock`, `v1.101.11`, `v1.101.12`, `v5`, `community`, and
`first-pass-verify`). Their hashes are:

```text
kernel.drv  58f9d0c62bdbdab7cfeee1c5e65bbe8b88ee962f8eecd3dd0515d1a1056d8bd6
drv_ui.drv  011f4e6a50738026ac641370f493c5df2e59adc435871d8a213e717604d2576a
```

This means the loader observations below apply to the firmware versions we
have been comparing, rather than to one community-only variant.

The nine dated extracted images currently available in the local corpus
(`2022-09-14`, `2022-11-17`, `2023-02-20`, `2023-07-26`, `2024-02-28`,
`2024-03-19`, `2024-04-24`, `2025-02-17`, and `2025-06-26`) also contain
byte-identical `ker_init.bin`, `ker_data.bin`, `ker_text.bin`, `ker_xsys.bin`,
and `kernel.drv` members. The hashes are respectively:

```text
ker_init.bin  f4c62848fb06ccbe6fba4323c4e29e46791c1abb25af648f0518eb0747492ec8
ker_data.bin  bea7c4c2df49b43717f1fc61e23fa75a3adad6c974489272760d1d924cce4158
ker_text.bin  035a58572630e59cccf7fd1f01e2d0bafbc8d04860e8f7b72555dffcf71288ac
ker_xsys.bin  deaf67edce7eca6397abd1ca91f742ccbe70f451bb4926c75c7e46862e3b2988
kernel.drv    58f9d0c62bdbdab7cfeee1c5e65bbe8b88ee962f8eecd3dd0515d1a1056d8bd6
```

For these nine dated releases, repeating kernel-support decompilation would
not add independent code evidence. This does not establish identity for the
two earlier dated releases that are absent from the extracted local corpus.

## Resident addresses

The firmware's `dec.bin` contains the fixed kernel-component address table.
Two mappings are independently supported by code references:

- `ker_init.bin` at `0xbfc32000`: its code forms pointers such as
  `0xbfc32fac`, which land on the local data/string area in that segment.
- `kernel.drv` at `0xbfc08000`: this is also the EBase value installed by the
  kernel init routine (`FUN_bfc32224`).

The headless Ghidra dumps used for these findings are generated outside the
repository under `/private/tmp/m30-kernel.c` and `/private/tmp/m30-kerinit.c`.
The raw input files in the repository remain unchanged.

## What the current `kernel.drv` actually proves

`tools/emu/yqhx.py` provides a verified **file-format** parser: the extracted
`kernel.drv` header has a `yqhx` marker, one resident segment at
`0xbfc08000`, and the BSS range recorded in the header. The sparse host
materializer in `tools/emu/driver_loader.py` applies those declared bytes and
zeros without executing them. This is useful emulator input, but it is not
proof of the resident kernel's copy routine.

The earlier interpretation of `FUN_bfc0a408` and `FUN_bfc0a578` as generic
`yqhx` loaders was too strong. A fresh Ghidra 12.2 mapping of the current
community/stock-identical `kernel.drv` payload (`file 0x200 -> runtime
0xbfc08000`) gives these boundaries:

- `FUN_bfc0a408` writes two resident-table entries, calls two indirect
  helpers, clears a global, and returns;
- `FUN_bfc0a578` copies one argument into three caller-provided locations and
  calls two indirect helpers;
- `FUN_bfc0a6a0` calls an indirect helper three times;
- `FUN_bfc0a808` writes one value into several state locations;
- `FUN_bfc0a944` stores one argument through an output pointer;
- `FUN_bfc0ac98` terminates at bad instruction data in the mapped segment.

None of those bodies contains the previously claimed file open, `0x4c`/`0x44`
header read, segment-copy loop, or BSS-clear loop. The exact reproducible
probe is:

```sh
bash tools/ghidra/run_kernel_loader_probe.sh analysis/community/fw/kernel.drv
```

It now reports these as resident-table helpers. The generic `a408`/`a578`
slot variants retained in `tools/emu/driver_loader.py` are synthetic host
model cases for the file-format hypothesis, not an M30-specific execution
claim. They must not be used to justify a firmware patch or a complete boot
model until their indirect callers or fixed-ROM implementation are recovered.

Both function boundaries are reproducible with:

```sh
bash tools/ghidra/run_kernel_loader_probe.sh analysis/community/fw/kernel.drv
```

## `ker_xsys` exception-vector evidence

The extracted firmware's `fw.log` gives the fixed offsets and sizes of the
kernel support members: `kernel.drv` at `0x2000..0x6c00`, `ker_text.bin` at
`0x6c00..0x7800`, `ker_init.bin` at `0x7800..0x9000`, `ker_data.bin` at
`0x9000..0x9200`, and `ker_xsys.bin` at `0x9200..0x9a00` in `FWIMAGE.FW`.

Direct MIPS32 disassembly of `ker_xsys.bin` at its established runtime base
`0xbfc34000` shows that the exception entry at `0xbfc34074` reads COP0 EPC,
saves it at `0x13dd8`, saves the return address at `0x13ddc`, and jumps to
the fixed address `0xbfc06d6c`. The neighboring entry at `0xbfc340a4` reads a
byte at `0x9fc1a495`, indexes a four-byte table beginning at `0xbfc19974`,
and compares the result with `SP & 0x3ffff` before selecting an alternate
exception stack.

The first entry was checked against both the extracted member and its
`FWIMAGE.FW` copy. At member offset `0x74` (container offset `0x9274`), the
little-endian MIPS32 instructions are:

```text
0xbfc34074  mfc0   k0,$14
0xbfc34078  lui    k1,0x0001
0xbfc3407c  addiu  k1,k1,0x3dd8
0xbfc34080  sw     k0,0(k1)       # EPC -> 0x00013dd8
0xbfc34084  move   k0,ra
0xbfc34088  lui    k1,0x0001
0xbfc3408c  addiu  k1,k1,0x3ddc
0xbfc34090  sw     k0,0(k1)       # RA -> 0x00013ddc
0xbfc34094  lui    k1,0xbfc0
0xbfc34098  addiu  k1,k1,0x6d6c
0xbfc3409c  jr     k1             # 0xbfc06d6c
0xbfc340a0  nop                   # delay slot
```

The preview trace can optionally represent these two pre-handler stores when
the caller supplies EPC and RA. It labels them as a `ker_xsys` entry candidate:
the values are input observations, and the available evidence does not prove
that every `break 0` callback or lazy-bank miss takes this specific entry.
Nothing after the jump is modeled.

This is relevant to far calls because it proves that an exception/dispatch
path exists outside the extracted UI driver. It does **not** yet prove that
the `0x3ffff` comparison performs bank loading: the same code can be an
exception-stack guard, and the final `0xbfc06d6c` handler is in fixed ROM or
another component not present in the extracted `.drv` members. The current
emulator therefore keeps far entries lazy and unresolved. The multi-segment
Ghidra mapping used for this check is reproducible with
`tools/ghidra/MapSegments.java`.

A byte-signature sweep across the extracted `FWIMAGE.FW`, `kernel.drv`,
`NORHWSC.BIN`, `NORFWSC.BIN`, and all `ker_*.bin` members finds the exact
`0xbfc06d6c` sequence only in `ker_xsys.bin` as a jump target; no extracted
member contains the handler body. The reproducible probe is:

```sh
bash tools/ghidra/run_kernel_exception_probe.sh
```

For the present image, the safest interpretation is “firmware exception entry
into an unavailable fixed handler”, not “a complete bank loader recovered”.

The extracted kernel's earlier ExcCode-vector path has now also been executed
in Ghidra p-code by `tools/ghidra/run_kernel_exception_dispatch_probe.sh`.
It runs the real MIPS32 instructions at `0xbfc08180`, after checking the mapped
bytes against `kernel.drv` file offset `0x380`. With the handler table seeded
in synthetic RAM, ExcCode 9 selects the statically installed candidate
`0xbfc06f18` even with Cause `0x80000027`; ExcCode 8 selects a distinct
synthetic address with Cause `0x20000023`. These inputs show that bits outside
`Cause & 0x7c` do not change the selected table slot. Both runs verify the
saved EPC, RA, and unmasked Cause words. This confirms the vector's table-index
and branch behavior only: the actual runtime table was not observed, and
neither fixed-ROM target was executed.

The table constructor is now separately exercised from the hash-pinned
`ker_init.bin`: `tools/ghidra/run_kernel_exception_table_init_probe.sh` maps
the extracted init code beside `kernel.drv`, runs the real MIPS32 constructor
and MIPS16 setter, and stops at `0xbfc32c5c` before later global
initialization. With a sentinel table, all entries 0..13 match the statically
recovered defaults and overrides, while entries 14..31 remain untouched.
This closes the p-code check for extracted table-construction logic; it does
not prove that the device executed this constructor during boot, and it does
not execute fixed-ROM handlers or the later ROM `memset` calls.

The `ker_init.bin` segment entry at `0xbfc32000` has now been disassembled
from the hash-pinned extracted bytes. Its straight-line setup calls
`FUN_bfc32224`, `FUN_bfc322f8`, and `FUN_bfc32440`, then loads the saved
arguments and directly calls the table constructor at `0xbfc32bc8` from
`0xbfc32074` (delay-slot instruction at `0xbfc32078`). This proves the
constructor is part of the extracted segment's entry path. It does **not**
prove that the device bootloader transfers control to `0xbfc32000`: the
available `fw.log`, `dec.bin`, and module manifest do not contain that
handoff.

The first pre-constructor helper, `FUN_bfc32224`, is now executed from the
actual extracted segment entry with the hash-pinned p-code probe
`tools/ghidra/run_kernel_init_cp0_probe.sh`. The probe continues through the
next caller instructions and stops at the first unresolved reset-helper
pointer `0xbfc045f5` (aligned execution PC `0xbfc045f4`), before executing it.
It verifies that the caller prepared `a0=0x9fc13d00`, `a1=0`, `a2=0xe0`,
with return address `0xbfc32060`. The entry first sets up its stack and writes
`1` to `0xc012001c`. A CP0 `setCopReg` callback writes the actual Ghidra CP0
register varnodes, while calls from the helper to `0xbfc05000` and
`0xbfc05028` are logged and return-stubbed (`v0=0`). The code does not
consume either return value. In 364 steps, the probe verifies
`Status=0`, `IntCtl=0`, `SRSCtl=0`, `SRSMap=0`, `Cause=0x00800000`, and
`EBase=0xbfc08000`; it also verifies `0x1fc00000` at
`0xc00a004c+4*i` for `i=0..21` and at `0xc00a00c0+4*i` for `i=0..3`.

This is a bounded segment-entry control-flow and register-write check, not a
model of the `0xbfc05000`/`0xbfc05028` services' possible interrupt side
effects or the `0xbfc045f5` reset helper. It verifies the actual caller
arguments at that first unresolved edge, but does not prove that the helper
clears the requested range. `FUN_bfc322f8`'s fixed-ROM boundaries are now
exercised by a separate bounded p-code probe, but the helper effects remain
opaque. The callback word at `0x9fc1aad8`, its source bytes, and the actual
boot-stage mapping are not established by the extracted `ker_init.bin`. Do
not substitute host `memset`/`memcpy` based only on the call shape.
`FUN_bfc32440` now has a bounded p-code probe at its extracted entry. It runs
the extracted instructions to return in 250 emulated steps, including seven
synthetic MIPS16 return-stub instructions. It uses `0xa5a5a5a5` seeded RAM-backed
MMIO words, and intercepts seven fixed-ROM calls with synthetic zero-return
MIPS16 stubs. The probe checks all 15 final MMIO words and the RAM fields
written by the function. This verifies the caller's instruction-level
read/modify/write masks and address calculations for the chosen seed; it does
not reveal the hardware register meanings, real reset values, or fixed-ROM
side effects. Repeating the run with all seven synthetic returns set to
`0xdeadbeef` also passed the checks: both profiles took 250 steps, reached the
function return, logged the same seven checked call edges and arguments, and
produced the same checked MMIO values. Its PC-trace digest also matched the
all-zero baseline. This supports that changing the
synthetic scalar returns preserved these checked outcomes in these runs; it
does not by itself identify the fixed-ROM services' behavior. Possible
service side effects are outside the model. Consequently, the complete
entry-to-constructor path remains unverified.

The differential was then isolated by call ordinal. In seven more runs,
exactly one of the seven ROM-call returns was set to `0xdeadbeef` while the
other six returned zero. Every run passed the 250-step, call-edge/argument,
function-return, RAM, and MMIO assertions. Thus none of the seven individual
scalar substitutions changed those checked outcomes. Additionally, the
SHA-256 of the ordered instruction-boundary sequence (32-bit PC plus
`ISA_MODE` and `RELP` bytes) matched the all-zero baseline for the all-sentinel
run and every one-call-at-a-time profile:
`079eb760cddc1e581c7c4aba54dbff55495e387cc0471577e41cf0e6ffed8eae`.
This shows that the synthetic caller-side PC sequence and mode tags stayed
identical for each individual scalar substitution. It does not execute the
real ROM bodies. Interactions between multiple nonzero returns and real
ROM-service effects are not tested.

### What the three entry-path helpers do, and what remains unknown

The extracted instructions support these bounded descriptions:

- `FUN_bfc32224` writes zero to CP0 `Status`, `IntCtl`, `SRSCtl`, and
  `SRSMap`; it sets `Cause` to `0x00800000`, installs `EBase=0xbfc08000`,
  and writes the same
  `0x1fc00000` value to 22 interrupt-controller slots at `0xc00a004c+4*i`
  and four more at `0xc00a00c0+4*i`. It also calls `0xbfc05000` and
  `0xbfc05028`; their return values are unused, but their possible hardware
  side effects are not known.
- `FUN_bfc322f8` calls `0xbfc045f5` with `(a0=0x9fc1a48c, a1=0,
  a2=0x7f4)`, then `0xbfc04635` three times with argument triples
  `(0x9fc1aad8,0x9fc12000,0x14)`,
  `(0x9fc1a980,0x9fc12014,0x20)`, and
  `(0xbfc080f8,0x9fc12200,4)`. These shapes are consistent with clear/copy
  operations, but the fixed-ROM implementations are unavailable. The
  argument direction has a stronger data-flow constraint than the helper
  name: the preceding clear covers `0x9fc1a48c..0x9fc1ac80`, including
  `0x9fc1aad8`; after the three candidate copy calls, the code loads the
  first word at `0x9fc1aad8` and uses it as a `jalr` target. If
  `0xbfc04635` is a conventional copy routine, its first argument must be the
  destination for the first call for this path to install that callback
  pointer. This is a strong ABI hypothesis from the consumer data flow, not
  execution evidence for the unavailable ROM helper. A separate
  `NORBREC.BIN` MIPS16 setup routine calls the unresolved helper with
  destination-like arguments `0x9fc12000`/`0x14` and `0x9fc12014`/`0x20`, and
  writes fields at `0x9fc12200`/`+0x204`. This is a concrete same-address
  connection between the earlier NOR setup and later `ker_init` inputs; the
  NOR helper's copy direction/effect and the runtime handoff/order are still
  unknown. See [NOR boot-record observation](findings-tools-and-norboot.md#nor-boot-record-observation).
  The source addresses `0x9fc12000`, `0x9fc12014`, and `0x9fc12200` lie in the first 96 KiB of the
  `0x9fc00000` address window. Public ATJ2127 tooling reports a Boot-ROM view
  in this range during ADFU: ordinary `read_mem` returns zeros, while a
  ROM-reading payload uses `read_mem2`. This makes the offsets useful ROM-dump
  targets, but does not establish the address mapping during normal M30
  kernel initialization. They could be ROM-backed there or a preinitialized
  RAM source; the runtime mapping and bytes remain unknown. It then calls the
  loaded word indirectly with
  `(a0=0xfffffff0, a1=0xbfc0855d, a2=0)`. The indirect target depends on the
  unresolved source contents. The function later reads a word from
  `0x9fc12204` into `0xbfc32fc4`; that source word is outside the preceding
  four-byte copy range.
- `FUN_bfc32440` writes `0x1a` to byte address `0x9fc1a8a8` and halfword
  address `0x9fc1a8aa`; these addresses correct the previous note's sign-
  extension arithmetic error. It writes derived timing-like values at
  `0x9fc1aab0`, `0x9fc1aab4`, and `0x9fc1aab8`. Its p-code probe confirms
  seven ROM call edges: five to aligned PC `0xbfc07a50` (odd pointer
  `0xbfc07a51`), one to `0xbfc04fb8` (pointer `0xbfc04fb9`, `a0=1`), and one
  to `0xbfc04f60` (pointer `0xbfc04f61`, `a0=120`). Returns were synthetic
  zeros or `0xdeadbeef` and MMIO started at `0xa5a5a5a5`; both runs took 250
  steps, reached the function return, matched the seven checked ROM-call
  edges/arguments, and produced the same 15 final register values. This
  supports that changing the synthetic scalar returns preserved these
  checked outcomes in these runs. The ordered PC+ISA_MODE+RELP trace SHA-256
  matched between the all-zero baseline, all-sentinel profile, and all seven
  one-call-at-a-time profiles.
  This validates the extracted caller path and probe, not actual register
  reset values, peripheral meanings, or ROM-service behavior.
  The seven one-call-at-a-time `0xdeadbeef` profiles also passed all assertions;
  this isolates individual return substitutions but not interactions among
  multiple changed returns.

Reproduce the bounded check with:

```sh
bash tools/ghidra/run_kernel_init_mmio_probe.sh <hash-matching-ker_init.bin> zero
bash tools/ghidra/run_kernel_init_mmio_probe.sh <hash-matching-ker_init.bin> sentinel
bash tools/ghidra/run_kernel_init_mmio_return_sweep.sh <hash-matching-ker_init.bin>
```

This is not a full semantic interpretation of those functions: the first has
unknown external side effects, the second depends on unavailable source bytes,
the fixed-ROM copy helper, and the normal-boot memory map, and the third
depends on peripheral reset state and ROM services. Accordingly, the call
targets remain unnamed and no complete initialization behavior is inferred
from the helper names or argument shapes.

### `FUN_bfc322f8` p-code call-boundary probes

`tools/ghidra/run_kernel_init_template_probe.sh` executes the actual
hash-pinned `ker_init.bin` instructions from `FUN_bfc322f8` (SHA-256
`f4c62848fb06ccbe6fba4323c4e29e46791c1abb25af648f0518eb0747492ec8`). It
records, then intercepts the call sites at aligned PCs `0xbfc045f4` and
`0xbfc04634`. It checks that the raw call register contains the corresponding
tagged values `0xbfc045f5` and `0xbfc04635`, and that each return address is
four-byte aligned. The probe does not execute either MIPS16 ROM target: on
each synthetic return it sets `ISA_MODE=0` and resumes at RA. This is a
bounded caller continuation, not a test of MIPS16 interworking. The observed
first four tuples are `(0x9fc1a48c,0,0x7f4)`,
`(0x9fc1aad8,0x9fc12000,0x14)`,
`(0x9fc1a980,0x9fc12014,0x20)`, and
`(0xbfc080f8,0x9fc12200,4)`. Those are actual AP call arguments; the ROM
helpers' memory effects are not modeled.

The native probe now has a discriminating copy-direction experiment. In
`forward` mode, the destination callback slot starts zero, the only synthetic
callback pointer is placed at source `0x9fc12000`, and the harness applies a
source-to-destination byte copy at each `0xbfc04635` boundary using the observed
arguments. The real caller then reaches `0x9fc27f00` in 54 steps with
`(a0=0xfffffff0, a1=0xbfc0855d, a2=0)`. In `reverse` mode, the harness applies
destination-to-source copies instead; the callback slot stays zero and the
caller reaches an indirect target of zero, which the probe recognizes as the
negative control. `seeded` mode retains the earlier fixture that preloads the
destination callback slot without modeling a copy.

This shows that source-to-destination copying is required by this caller's
data flow and is sufficient to install the callback pointer under the
synthetic fixture. It does not prove the absent ROM routine performs that
copy, that the callback exists at this source address during normal boot, or
that `0x9fc12000` is ROM rather than RAM in the boot map. The known ROM body
and its effects remain unobserved.

The `0xbfc045f5` call shape is better constrained than its name: segment entry
also calls it with `(0x9fc13d00, 0, 0xe0)`, and `FUN_bfc322f8` later calls it
with `(0x00013d00, 0, 0xe0)`. All three callsites pass a start address, zero,
and an end-minus-start length. This strongly supports a clear/zero routine,
but its MIPS16 body is absent, so the effect remains a hypothesis.

The native p-code probe now runs a differential synthetic-effect check at the
`FUN_bfc322f8` clear boundary. It fills the requested `0x9fc1a48c..0x9fc1ac80`
range with `0xa5`, places a canary at the exclusive end, then compares a
zero-fill shim with a no-effect shim using the actual `(a0=0x9fc1a48c,
a1=0, a2=0x7f4)` call. In zero-fill mode, every 32-bit word in the range
becomes zero; in no-effect mode they remain `0xa5a5a5a5`; the canary remains
`0x13579bdf` in both. Both continuations reach the same synthetic callback
in 54 steps despite the modeled memory differences. With zero-fill held
constant, synthetic returns of zero, the destination, and `0xdeadbeef` also
reach the same callback with the same checked arguments, so no
return-value-dependent control-flow or callback-argument effect was observed
on this path. This does not rule out other return-dependent effects in
unexecuted paths. Stock and community `ker_init.bin` both pass the memory
effect controls at the pinned SHA-256; both pass the sentinel-return case as
well. This validates the probe's range/ABI model and constrains this caller's
observed continuation; it does not identify the real ROM implementation or
prove that zero-fill is required for boot correctness. Reproduce with
`tools/ghidra/run_kernel_init_native_mode_probe.sh <ker_init.bin> forward
clear-zero return-zero` and repeat with `return-dst`, `return-sentinel`, or
`clear-no-effect return-sentinel`.

The probe also has a `callback-return` mode. It records the callback's actual
return PC (`0xbfc323d4`), writes synthetic exception-state words at
`0x13dcc/0x13dd0/0x13dd4`, and resumes the real extracted caller there with a
no-op callback return. The caller proceeds without a branch or intervening
call to the second `0xbfc045f5` boundary at callsite `0xbfc32420`, with
`(a0=0x13d00, a1=0, a2=0xe0)`, then returns from `FUN_bfc322f8` to the harness.
Under the zero-fill shim, every word in `[0x13d00,0x13de0)` is zero and the
three synthetic exception-state words are cleared; under no-effect, the
synthetic words remain and the canary at `0x13de0` is preserved. The same
no-effect/sentinel-return case also passes on stock; the zero-fill and
destination-return variants were run on community. With the clear shim's return
set to zero, the destination, or `0xdeadbeef`, the extracted caller returns
that value in `v0`; this is caller return-value propagation, not evidence of
what the unavailable ROM helper itself returns. The callback behavior and
the memory contents are fixtures. Numeric overlap with the exception vector's
save slots does not establish the device's physical memory map, nor that this
helper runs during normal boot. Reproduce with
`tools/ghidra/run_kernel_init_native_mode_probe.sh <ker_init.bin> forward
clear-zero return-zero callback-return`; vary the clear and return modes as
above.

`tools/ghidra/run_kernel_init_native_mode_probe.sh` runs the native MIPS
p-code scenarios on hash-matching stock and community `ker_init.bin`:

- `forward` (default) copies each observed `(dst,src,len)` at the absent
  `0xbfc04635` boundary and reaches the callback from a source-only pointer.
- `reverse` copies `(dst,src,len)` in the opposite direction and asserts the
  real caller reaches a null indirect target instead of the callback.
- `seeded` retains the original preloaded-destination control.

Each scenario also confirms four entries into the two ROM call sites with
`ISA_MODE=1`, exact return addresses `0xbfc32348/6c/90/b4`, and synthetic
MIPS16e `jrc ra` (`0xe8a0`) returns to MIPS32 mode with `RELP=1`. In `forward`,
the callback receives `(a0,a1,a2)=(0xfffffff0,0xbfc0855d,0)` after 54 steps.
These are caller data-flow experiments under explicit shims: no result proves
the unavailable ROM routine's implementation or normal-boot mapping. The
earlier `EmulatorHelper` probe remains a simpler call-boundary cross-check.

Example:

```sh
bash tools/ghidra/run_kernel_init_native_mode_probe.sh <hash-matching-ker_init.bin> forward
bash tools/ghidra/run_kernel_init_native_mode_probe.sh <hash-matching-ker_init.bin> reverse
```

The ATJ2127 Boot ROM map is documented in the public
[`actions_flash` instructions](https://github.com/ilyakurdyukov/actions_flash/blob/main/README.md#instructions).
This is chip-family evidence, not a ROM dump or M30 hardware confirmation.

## Constructor-to-vector integration

`tools/ghidra/run_kernel_initialized_exception_probe.sh` now composes the
hash-pinned `ker_init.bin` constructor/setter with the real MIPS32 vector from
`kernel.drv` in one Ghidra p-code machine. The constructor writes ExcCode 9's
entry at `0x9fc1a9c8`; the vector then runs with Cause `0x80000027`, computes
the table byte offset `Cause & 0x7c == 0x24` (ExcCode slot 9), saves EPC/RA/Cause at the recovered state words, and
branches to `0xbfc06f18`. The probe passed after 1,177 constructor steps and
17 vector steps. It stops on the handler address before executing it.

This closes the extracted-code connection between the table writer and vector
consumer; unlike the earlier isolated dispatch probe, the handler table is
populated by the real constructor rather than seeded with a synthetic ExcCode
9 value. It still uses synthetic RAM for the table/state and does not prove
the device boot sequence called this constructor. The fixed-ROM handler body
and its behavior remain unavailable.

The same constructor-to-vector sequence now also runs in QEMU/Malta using
`tools/emu/run_malta_constructor_vector_probe.py`. It executes the hash-pinned
constructor and MIPS16 setter, then reaches the same `0xbfc32c5c` stop boundary
used by the p-code probe. The BIOS builder redirects the setter table-base
literal and vector table-base instruction into synthetic RAM, and replaces
the boundary instruction plus its delay slot with a jump to the harness
checker. All table entries and the subsequent ExcCode 9 vector dispatch pass
on QEMU `24Kf` and `34Kf`. This is evidence for the extracted code path under
QEMU's CPU model; it is not evidence of the device's bootloader transfer,
fixed-ROM handler behavior, or ATJ2127 peripheral behavior.

## `drv_ui.drv` header and public bank formula

For the M30 `drv_ui.drv` header, the group-start fields are:

```text
header + 0x48 = 0x00001600   A group file start
header + 0x4c = 0x00006600   B group file start
header + 0x50 = 0x00010600   C group file start
```

The public ATJ2127 SDK's `link_base.xn` and `ui_driver.xn` define the
corresponding bank windows as:

```text
A bank 0: 0x16024c00, size 0x400
B bank 0: 0x26025000, size 0x800
C bank 0: 0x36021a00, size 0x400
next bank: +0x40000
```

The M30 bank table has the same three groups and the same bank sizes. This
makes the public formula a high-confidence runtime-address candidate for the
M30 image, and it is now exposed by `tools/inspect_drv.py --runtime-base`.
The final M30-specific proof still requires following the kernel's indirect
bank-window lookup or observing a resident pointer at runtime; the tool keeps
the label `public_runtime` to avoid silently presenting a candidate as a
hardware observation.

## Cross-version bank corpus check

The six locally archived `drv_ui.drv` files (`stock`, `v1.101.11`,
`v1.101.12`, `v5`, `community`, and `first-pass-verify`) are identical at
the byte level: each is 75,264 bytes and exposes exactly 24 banks:

```text
A00-A09  B00-B09  C00-C03
```

None of those six images contains A19 or B16. The A19/B16-qualified pointers
in the B3 literal pool are therefore not alternate copies hidden in another
locally archived M30 UI driver. They remain external-provider or
resident-dispatch candidates, which is why `LazyBankRuntime` records them but
does not execute them.

A second scan across every extracted `.drv` member in the community image found
the same negative result: the five exact little-endian pointer values
`0x164c9001`, `0x164c90ed`, `0x26089471`, `0x260894e9`, and `0x26409401` occur
within `drv_ui.drv` (including its B3 literal pool and related embedded
code/literal regions), but not as payload occurrences in the other driver
files (`kernel.drv`, `drv_lcd.drv`, `drv_dac.drv`, storage, USB, key, and audio
drivers). The copies in `dec.bin`/`FWIMAGE.FW` are container-level duplicates
of those firmware bytes. The scan is reproducible with:

```sh
PYTHONPATH=tools/emu python3 tools/emu/provider_scan.py analysis/community/fw
```

This container-duplicate conclusion was checked more strictly across the nine
dated extracted images from `2022-09-14` through `2025-06-26`. For every
occurrence of each observed A17/A19/B02/B16 pointer in `afi/FWIMAGE.FW` and
`dec.bin`, the surrounding 0x60-byte window (0x20 bytes before and 0x40 bytes
after the pointer, clipped at file boundaries) occurs verbatim in that image's
`fw/drv_ui.drv`. The result was 34 occurrences and 0 unmatched windows for
each of the nine images. Thus the additional container offsets are alternate
views of the known UI-driver consumer, not evidence of a second provider
implementation.

This window comparison is deliberately stronger than a pointer-presence
scan: it preserves the surrounding literal/call-site bytes and avoids treating
an unrelated ROM constant as a provider. It still says nothing about fixed
ROM, which remains outside the extracted member set.

The comparison is reproducible without an ad-hoc script:

```sh
PYTHONPATH=tools/emu python3 tools/emu/provider_context_probe.py \
  /Users/berkankarakus/Projects/m30-inputs/extracted/2025-06-26
```

The probe is intentionally limited to the known container views and returns a
failure for any unmatched context; it does not classify arbitrary data as a
provider.

The same scan widened to every extracted firmware member (`--all-files`)
produces exactly the same 16 occurrences, all still in `drv_ui.drv`; no `.ap`,
`.sty`, `.bin`, DSP, or other extracted file provides a second copy of any
observed far pointer. This closes the remaining local-file provider search
without claiming anything about fixed ROM.

```sh
PYTHONPATH=tools/emu python3 tools/emu/provider_scan.py \
  --all-files analysis/community/fw
```

For the provider-only negative check, exclude the known consumer that owns the
literal pool:

```sh
PYTHONPATH=tools/emu python3 tools/emu/provider_scan.py \
  --all-files analysis/community/fw --exclude-name drv_ui.drv --require-zero
```

This currently finds zero copies of the A17/A19/B16 values in the other
extracted members. The unfiltered scan is still useful because it records the
consumer-side occurrences and their offsets.

The raw-byte scan was also rerun for the six resident-table/mask candidate addresses
and the `0xbfc06d6c` exception target across every extracted member. All seven
values have zero little-endian occurrences in the local tree. This agrees
with the Ghidra reference result and further narrows the missing call path to
indirect runtime tables or fixed ROM rather than an overlooked extracted
literal.

### `dec.bin` is not an extra ROM dump

The decrypted AFI image at `analysis/community/dec.bin` is a container copy,
not a second ROM source. Each of `kernel.drv`, `ker_text.bin`, `ker_init.bin`,
`ker_xsys.bin`, `drv_ui.drv`, and `drv_dac.drv` occurs exactly once in it at
the corresponding extracted-member offset. A direct scan also finds no
little-endian `0xbfc06d6c` pointer in the container. The reproducible check is:

```sh
PYTHONPATH=tools/emu python3 tools/emu/dec_container_probe.py \
  analysis/community
```

Therefore the missing exception handler cannot be recovered by remapping
`dec.bin`; it is still outside the available M30 dump.

The extracted kernel-support scope adds the same boundary from another angle:
MIPS16 binutils disassembly finds zero `break` instructions in `ker_text.bin`,
`ker_init.bin`, and `kernel.drv`. The resident audio/UI `break 0` envelopes
therefore have no extracted kernel-side dispatcher to call. This does not
exclude an MIPS32 service path, but together with `ker_xsys`'s fixed jump it
places the selector implementation outside the available members. Re-run it
with:

```sh
PYTHONPATH=tools/emu python3 tools/emu/kernel_syscall_scope_probe.py \
  analysis/community/fw
```

The resident `kernel.drv` was also scanned at its established `0xbfc08000`
base for constants relevant to the proposed far-pointer translation. It
contains no literal `0x00009000` prefix and no literal `0x00025000` B-window
base. It does contain one `0x0003ffff` mask at `0xbfc0a9ac`, consumed as a
generic address mask by `FUN_bfc0a944`, plus unrelated `0x400` and `0x40000`
constants. This does not identify a bank-window translator; it is negative
evidence against placing the observed `0x9000` conversion directly in the
extracted resident image. The check is reproducible with:

```sh
bash tools/ghidra/run_kernel_bank_boundary_probe.sh \
  analysis/community/fw/kernel.drv
```

This rules out the simplest explanation that A19/B16 are provided by a second
extracted driver; the remaining search boundary is the resident dispatcher or
unavailable fixed ROM code.

### External ATJ2127 overlay cross-check

The public `actions_flash` reverse-engineering notes for a different ATJ2127
device/firmware describe a YQHX overlay window and tagged MIPS16 pointers of
the form `0x73...5a00...`. This is useful architecture evidence, but it is not
automatically an M30 ABI: the target notes identify an Actions US212D image at
V1.101.25, not the M30 image analyzed here. See the
[`actions_flash` RE notes](https://github.com/kilicdev/actions_flash/blob/main/RE_NOTES.md#12-binary-executable-format--overlay-system-yqhx).

As a local compatibility check, the M30 extracted `.ap`, `.drv`, and `.bin`
members were scanned for the exact masked signature
`(word & 0xff00ff00) == 0x73005a00`; the result was zero occurrences. The
observed M30 pointers (`0x164c...` and `0x2608...`) therefore must not be
rewritten using that external tag formula without a new M30-specific proof.
The scan is reproducible with:

```sh
PYTHONPATH=tools/emu python3 tools/emu/scan_pointer_signature.py \
  --require-zero analysis/community/fw
```

The Ghidra-side occurrence probe adds the next boundary check:

```sh
bash tools/ghidra/run_drvui_pointer_probe.sh analysis/community/fw/drv_ui.drv
```

It finds 16 little-endian occurrences across the five values, all inside the
flat `drv_ui.drv` corpus. In that mapping Ghidra assigns no occurrence to a
recognized function body, which is consistent with literal pools or embedded
data. Because the flat loader's function discovery is heuristic, this is
supporting evidence only; the runtime B3 remap remains the stronger function
boundary for the `0x9924` pool. The full-file probe reports these file offsets:

```text
0x164c9001: 0x891c, 0x9964, 0xceec, 0x112d8
0x164c90ed: 0x8308, 0x8914, 0x9038, 0x992c, 0xced4
0x26089471: 0x8b58, 0x995c, 0xcee8
0x260894e9: 0x8b5c, 0x9960
0x26409401: 0x8938, 0x996c
```

Grouping those hits with a `0x40`-byte gap threshold produces seven clusters.
The B3 literal pool is the five-hit cluster at `0x992c..0x996c`; the other
clusters are nearby code/data regions or isolated copies. This is reproduced
by:

```sh
bash tools/ghidra/run_drvui_pointer_cluster_probe.sh \
  analysis/community/fw/drv_ui.drv
```

The cluster report is provenance evidence, not a claim that every nearby word
is executable code. The runtime B3 remap and the separate B02 probe remain the
authority for code-entry classification.

## Consequence for the marquee investigation

The fourth B window begins at file offset `0x9680`; the static marquee routine
starts at `0x9674`, immediately before that payload boundary. With B3 mapped
at `0x260e5000`, the function begins at `0x260e4ff4` (the same 12-byte
lead-in relationship). A full Ghidra remap of the B3 bytes decompiles this
address cleanly and reproduces the expected behavior: it clears `0x7c`
pixels, reads the two text records, applies the active width/offset state, and
submits the resulting text rectangles.

This is a strong static/runtime-address confirmation of the public bank
formula and of the marquee candidate. It is still not a physical-device
trace, so it should be used for emulation and call-edge work before any patch
is built.

The remap is reproducible with:

```sh
bash tools/ghidra/run_drvui_runtime_probe.sh
```

## B3 literal-pool call classification

The runtime remap puts the B3 literal pool at `0x260e52a4`, corresponding to
`drv_ui.drv` file offset `0x9924`. The important values are:

| Pool address | Value | Current classification |
|---|---:|---|
| `0x260e52ac` | `0x164c90ed` | bank-qualified external UI entry; target bank bits identify A group 6, bank 19 |
| `0x260e52b0` | `0xbfc09ce1` | resident UI entry near `bfc09ce0` |
| `0x260e52bc` | `0xbfc09c01` | resident system-operation entry; the even-address disassembly is a trap/syscall stub, not the clear implementation itself |
| `0x260e52c0` | `0xbfc0a8bd` | resident UI state setter at `FUN_bfc0a8bc` |
| `0x260e52c4` | `0xbfc0a8c9` | resident UI state setter at `FUN_bfc0a8c8` |
| `0x260e52dc` | `0x26089471` | bank-qualified external UI entry; target bank bits identify B group 6, bank 2 |
| `0x260e52e0` | `0x260894e9` | second entry in the same B2-qualified region |
| `0x260e52ec` | `0x26409401` | bank-qualified external UI entry; target bank bits identify B group 6, bank 16 |
| `0x260e52f0` | `0x9fc0bf98` | resident RAM callback/state pointer |
| `0x260e52f4` | `0x9fc0bf84` | resident RAM callback/state pointer |

The resident targets were checked by remapping the `drv_ui` resident sections
at `0xbfc09c00` and `0xbfc0a800`. The bank-qualified values have the expected
Actions address encoding, but their low-offset entry/stub area is not the
`0x25000` overlay window itself. They should therefore be modeled as lazy bank
entries first; treating them as ordinary direct calls would skip the bank
manager behavior that QEMU still lacks.

The resident remap is reproducible with:

```sh
bash tools/ghidra/run_drvui_resident_probe.sh analysis/community/fw/drv_ui.drv
```

It confirms that `0xbfc09ce0` is a no-op return, `0xbfc09c00` is a trap/syscall
wrapper, and `0xbfc0a8bc`/`0xbfc0a8c8` write the two resident state slots used by
the B3 path. The instruction dump also shows a second trap entry at
`0xbfc09c04`; therefore the B3 odd pointer `0xbfc09c05` must be modeled as a
MIPS16 pointer to that second entry, not as a simple alias of `0xbfc09c00`.
These are extracted resident helpers, not the missing A19/B16 providers or proof
of the ROM bank translator.

The B3 call shapes narrow down the semantic roles without pretending that the
M30 bank table is complete:

| Pointer | B3 call shape | Strongest public-SDK match | Confidence |
|---|---|---|---|
| `0x164c90ed` | one display-mode argument | `fix_switch_fontlib` in A19 | high |
| `0x164c9001` | descriptor, max width, result | `fixf_get_string_length` in A19 | high |
| `0x26089471` | first one-argument Arabic/Hebrew transform | `arabic_uni_join` in B2 | medium |
| `0x260894e9` | second one-argument Arabic/Hebrew transform | `arabic_ascii_turn` in B2 | medium |
| `0x26409401` | descriptor, region, mode | `put_string_line_sdimage` in B16 | high |

This is now represented by the host-only classifier in
`tools/sim/ui_calls.py`. It deliberately returns no label for an unobserved
pointer. The distinction matters: the M30 `drv_ui.drv` table currently ends at
A09/B09/C03, so A19 and B16 are not proven to be payloads in this particular
driver file. The public headers establish the signatures and the B3 call sites
establish the argument shapes, but the resident/provider that services these
far pointers remains to be mapped.

### Public A17 source-role cross-check

The public ATJ2127 UI linker script assigns `BANK_A_17` to
`bank_a_ui_putstring_sub_2.o`, not to a generic LCD-fill module. The matching
source file contains `bankaf_put_string_line_arabic_hook()`, `char_out_rect()`,
and `load_bgpic_sd()`: it prepares text for Arabic/RTL rendering, chooses
left-to-right or right-to-left character output, and records the background
image used by the SD-image text path. The source and linker script are
available in the public [PD196_ATJ2127 UI driver](https://github.com/Suber/PD196_ATJ2127/tree/maint/case/drv/ui)
reference tree.

This is useful role evidence for the unresolved M30 resident target
`0x164490a1` (`A17 + 0x0a1`). M30's `0xbfc09e34` forwarder passes three
arguments to that target only for a restricted display-mode/state range, which
is compatible with a text/provider hook. It does **not** prove that the M30
target has the public source's exact offset or implementation, and it does not
identify the marquee's solid-color or background-restore helper. The public
source therefore narrows the next search to the text/provider dispatch
boundary while leaving the host emulator fail-closed.

There is one additional loader clue. All five observed far entries use the
same `0x9000` logical prefix in their low offset. Subtracting that prefix gives
the following candidate offset in the corresponding overlay window:

```text
0x164c9001 -> A19 + 0x001
0x164c90ed -> A19 + 0x0ed
0x26089471 -> B02 + 0x471
0x260894e9 -> B02 + 0x4e9
0x26409401 -> B16 + 0x401
```

The B02 `+0x471` candidate lands on valid MIPS16 code when the real B02
payload is mapped at `0x260a5000`; Ghidra identifies it as a thunk into the
bank body. The pointer's low MIPS16 bit means the decompiler entry is the
aligned address `0x260a5470` (file offset `0x8af0`), while the host candidate
retains payload offset `0x471` from the pointer value. This is stronger than a
numeric coincidence, but it does not yet prove how the hardware or resident
loader translates the `0x9000` prefix, so the host model exposes it as
`payload_offset` and never executes the far pointer directly. The reproducible
probe is:

```sh
bash tools/ghidra/run_drvui_b2_probe.sh analysis/community/fw/drv_ui.drv
```

The paired B02 `+0x4e9` candidate is a useful negative result. Under the same
candidate mapping it lands at `drv_ui.drv` file offset `0x8b69`, inside the
literal/data area following the nearby B02 code; Ghidra reports bad
instruction data there. Therefore the second B3 call must not be emulated by
blindly jumping to `B02 + 0x4e9`. It may use a different lazy-entry/stub
translation, or the literal value may be an address into a provider not
present in this M30 `drv_ui.drv`. The host model now preserves both the bank
and candidate payload offset, while leaving code-entry validation separate.

As a separate cross-check, Ghidra's reference manager finds zero direct code
or data references in `kernel.drv` to the six recovered loader/mask candidates
(`FUN_bfc0a408`, `FUN_bfc0a578`, `FUN_bfc0a6a0`, `FUN_bfc0a808`,
`FUN_bfc0a944`, and `FUN_bfc0ac98`). Their bodies are present, but calls are
therefore likely reached through resident function tables, indirect pointers,
or code outside this extracted image. The check is reproducible with:

```sh
bash tools/ghidra/run_kernel_loader_refs_probe.sh \
  analysis/community/fw/kernel.drv
```

## Next investigation

The remaining useful kernel-side target is the indirect bank-window lookup,
not an already-understood resident driver installation path. We should map
the low-offset stub area for the A19/B2/B16-qualified entries and connect each
stub to the corresponding `drv_ui` bank or shared UI module. Until then, the
host emulator and Ghidra p-code probes remain the safe test surface.

The host-side `LazyBankRuntime.boundary_trace()` now records the conservative
exception/load/retry envelope as a host contract. It does not claim that the
six resident-table helpers above are the bank loader: the fixed exception
handler, its indirect tables, and the actual bank-copy implementation remain
unresolved. Missing providers and unvalidated entry offsets stop in the trace;
no raw far pointer is executed.

## Additional public sources checked

The following sources were checked after the initial `actions_flash` review.
They are useful as compatibility and recovery references, but none is treated
as proof of an M30-specific address or ABI.

### Public ATJ2127 SDK: update flow and filename handling

The public `Suber/PD196_ATJ2127` SDK contains source for its automatic-upgrade
menu. It defines the firmware filename as `UPGRADE.HEX`, also accepts the
display spelling `UPGRADE HEX`, opens the file from the root of the selected
storage volume, and asks for confirmation only after the file is found. The
same source formats the confirmation label from the filename rather than
requiring a runtime `%s` placeholder in the visible string.

This is useful for explaining the M30 upgrade-screen wording and for testing
the earlier spacing change, but the repository targets a different product
build. It must not be used as a drop-in source replacement for M30 UI code:

<https://github.com/Suber/PD196_ATJ2127/blob/maint/case/ap/ap_tools/menu_callback_autoupgrade_tools.c>

### Independent package parsers

Two independent tools provide cross-checks for the offline firmware boundary:

* Rockbox's `atjboottool` contains a maintained C implementation for unpacking
  Actions firmware containers. It is a good second parser for checking the
  outer update package before any modification is attempted:
  <https://github.com/Rockbox/rockbox/tree/master/utils/atj2137/atjboottool>
* `nfd/atj2127decrypt` provides an independent ATJ2127 `UPGRADE.HEX`
  decryptor/unpacker and documents a MIPS/QEMU debugging path for its own
  implementation. Its README explicitly notes that the generated decompiled
  code is ugly and that MBREC decoding is incomplete, so it is a validation
  oracle rather than a source of trusted boot code:
  <https://github.com/nfd/atj2127decrypt>

Neither parser should be allowed to rewrite the archived M30 tree silently.
The safe integration point is a compare-only check: package file names,
lengths, hashes, and YQHX metadata must agree before we use a result in the
emulator.

### Public ATJ2127 overlay ABI cross-check

The public `kilicdev/actions_flash` investigation log independently describes
the same broad execution model: YQHX overlay banks are copied into a fixed
execution window on demand, and MIPS16 system calls use a `break 0` path with
selector values in a register. Its example is an ATJ2127 reference/Bluetooth
build, not the M30 image, and its tagged-pointer constants do not match the
M30 A/B/C values. It is therefore useful as ABI corroboration only; it does
not identify the missing M30 `0xbfc06d6c` handler or authorize direct calls.

<https://github.com/kilicdev/actions_flash/blob/main/RE_NOTES.md>

### Recovery and read-only extraction boundary

The SanDisk Clip Sport recovery notes are for another ATJ2127 product, but
they provide a useful independent description of the boot chain: Boot ROM,
MBREC, BREC, then the firmware image, with ADFU recovery exposed as
`10d6:10d6`. They also describe raw-NAND readback as a separate operation
from flashing. This supports keeping our recovery model and our firmware
modification model separate:

<https://gist.github.com/roman-yepishev/737dfda3a0a853fe730286d3ce49fccd>

The same notes include a destructive hardware workaround for a failed device;
that procedure is explicitly out of scope here.

### USB/ADFU caution from a separate investigation

The recent Crystal701 investigation records that an ADFU identification
response can recover after a full power cycle, while a successful USB
identification does not guarantee that arbitrary memory reads are accepted.
This matches our practical rule: a future USB experiment must begin with a
read-only probe, record VID/PID and the exact response, and stop before any
`write_mem`, `write_flash`, or payload execution. It is a field report rather
than an M30 or ATJ2127 specification:

<https://gist.github.com/GOROman/934e9c522632b303065777bb154a4ef6>

### Sources deliberately not reused

The newer `sonigeez/actions-adfu` tool is documented for ATS30xx firmware and
its tested chip is ATS3085S4. Its dry-run and read-only ideas are useful, but
its loader and flash protocol are not evidence for this ATJ2127 M30 and are
not imported into the project:

<https://github.com/sonigeez/actions-adfu>

The practical outcome is a stronger offline/recovery boundary, not a claim
that we are ready to flash a custom image. We still need an M30-specific
provider/bank-window proof before modifying executable UI code with confidence.

## MIPS16 import-selector evidence

The B3 bank has a repeatable indirect-call pattern in which a `jalr` is
immediately followed by a MIPS16 delay-slot instruction `li a3,<selector>`.
The mapped B3 disassembly contains one selector-15 call at `0x260e5794`
immediately paired with one selector-17 call at `0x260e579e` in a second UI
function. Other indirect calls in the
same bank use different selectors, so this is evidence for a reusable import
ABI rather than proof that selectors 15/17 are the marquee operation. It is
stronger than a byte-pattern match because the values are attached to decoded
call sites, but it still does not establish the selector semantics or the
resident dispatch table that receives them.

The read-only cross-check is reproducible with:

```sh
bash tools/ghidra/run_drvui_import_probe.sh analysis/community/fw/drv_ui.drv
```

### Dated-corpus binutils cross-check

The same MIPS16 binutils scan was run against the nine hash-verified dated
HEX extractions currently available outside Git (2022-09-14 through
2025-06-26, excluding the three older `ACTS-FWT001` files that the current
unpacker cannot open). Every `drv_ui.drv` produced the same decoded import
summary:

```text
MIPS16 IMPORT SUMMARY calls=5 selectors=0:2,1:1,15:1,17:1
selector 0xf:  call 0x260e5794, delay 0x260e5796
selector 0x11: call 0x260e579e, delay 0x260e57a0
```

The two older common blobs are byte-identical with SHA-256
`4614c31a4175ca7fdf24ae5b2e2266afe063df373d6255cc115eb67d2ff8f323`; the
2024-03-19, 2024-04-24 and 2025-02-17 blobs are byte-identical with SHA-256
`f9d63d68f7d66f8f83f109044d1c7e91e415d9feec49ca4663d5119867aee0b2`; and the
2025-06-26 blob is a third variant with SHA-256
`011f4e6a50738026ac641370f493c5df2e59adc435871d8a213e717604d2576a`.
Despite those byte-level variants, the decoded selector/call boundary is
unchanged across all nine inputs. This is independent instruction-decoder
evidence for the existing Ghidra result, not proof that selectors `0xf` or
`0x11` have identical semantics in every firmware release.

The probe now maps the B3 function together with the extracted resident
wrapper. It verifies that the selector-15 and selector-17 calls use the same
four-argument resident target at `0xbfc09c05`, while the target decompiles to
the trap/status-byte wrapper at `0xbfc09c00`. It still reports selector
numbers only at the M30 execution boundary; it does not model them as
rendering, storage, or bank-loading operations until an M30-specific
dispatcher path is recovered.

The probe also dumps the B3 literal table entries at `0x260e59e8` and
`0x260e59ec`, confirming that the symbolic call target used by both selector
sites is the actual `0xbfc09c05` pointer, while the neighboring system-call
entry is `0xbfc09c01`. This closes the pointer-value gap without assigning
meaning to either selector.

### Public UI-operation ordinal cross-check

The public SDK gives a useful, but deliberately separate, comparison. Its
`ui_cmd_e` enum and `ui_driver_operations` table place
`UI_SCROLLSTRING_EXT` at ordinal `15` and `UI_GET_DC_STATUS` at ordinal `17`.
Those are exactly the two selector values observed in the M30 B3 delay slots.
The public header also shows that the application-facing `ui_op_entry()` call
passes the command as its fourth argument. That matches the M30 call shape
around `0xbfc09c05` in the second B3 function, where the same target is called
with selectors `0xf` and `0x11` and three payload arguments.

This raises confidence that the values are UI-operation selectors rather than
arbitrary rendering constants, but it is not an M30 dispatcher proof: the
public SDK targets a reference product and the M30 resident table is still
unavailable. The host layer therefore exposes these names only through the
reference-only `tools/sim/ui_imports.py` module and keeps the selectors
unresolved for execution.

## B3 trailing pointer table

The second B3 UI function at runtime `0x260e5780` ends at the B3 bank boundary.
The next mapped data area begins at file offset `0x9e80`, runtime
`0x260e5800`. Its first pointer words used by the function are at runtime
`0x260e59e8` (file `0xa068`):

| Runtime address | Value | Current classification |
|---|---:|---|
| `0x260e59e8` | `0xbfc09c05` | resident UI/system entry candidate |
| `0x260e59ec` | `0xbfc09c01` | resident system-operation entry |
| `0x260e59f0` | `0x26209401` | B08-qualified lazy-bank candidate, payload `+0x401` |
| `0x260e59f4` | `0x261c9694` | B07-qualified lazy-bank candidate, payload `+0x694` |
| `0x260e5a10` | `0x26109751` | B04-qualified lazy-bank candidate, payload `+0x751` |

The first two values match the resident call convention already seen in the
B3 literal pool. The other three use the same `0x9000` low-offset encoding as
the earlier A19/B2/B16 candidates, but their payload offsets do not by
themselves prove an entry point or a direct call target. In particular, the
table is evidence for a shared/lazy dispatch data structure, not evidence that
the missing provider banks are present in this M30 `drv_ui.drv`.

The read-only mapping and word dump are reproducible with:

```sh
bash tools/ghidra/run_drvui_b3_table_probe.sh analysis/community/fw/drv_ui.drv
```

## B3 candidate bank bodies

Mapping only the nominal `0x800`-byte bank windows leaves the candidate
functions' literal pools outside the Ghidra memory map. Including the next
`0x800` bytes after each bank produces coherent decompilations for all three
entries referenced by the B3 table:

| Raw pointer | Candidate bank/offset | Public runtime entry | Observed decompilation shape |
|---|---|---:|---|
| `0x26109751` | B04 `+0x751` | `0x26125750` | six-argument UI/text renderer; kernel and filesystem calls |
| `0x261c9694` | B07 `+0x694` | `0x261e5694` | two-argument lookup/dispatch helper returning `ushort` |
| `0x26209401` | B08 `+0x401` | `0x26225400` | descriptor/line renderer with alignment and clipping flags |

The odd low bit is handled as MIPS16 mode metadata: the first and third raw
values enter at the preceding aligned address. This is now a stronger
candidate-body result, not proof that the raw pointer can be called directly
by the host emulator. The provider bank and dispatcher behavior still need a
runtime observation or an extracted resident implementation before execution
is enabled.

The host address model exposes these three derived addresses as
`candidate_runtime_entry`; it deliberately keeps the field separate from an
executable function pointer. This preserves the useful Ghidra/public-window
mapping while keeping unresolved bank dispatch fail-closed.

The reproducible probe is:

```sh
bash tools/ghidra/run_drvui_b3_candidate_probe.sh analysis/community/fw/drv_ui.drv
```
