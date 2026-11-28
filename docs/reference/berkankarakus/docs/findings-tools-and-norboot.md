# Existing tool source audit and NOR boot-record lead

This note records what the source code already available on the analysis host
can and cannot establish for M30 boot and ROM research. No tool was run against
the device, no firmware was written, and no dependencies were downloaded.

## Rockbox source audit

The local Rockbox tree has useful protocol and disassembly references, but its
ATJ213x assumptions are not proof of ATJ2127/M30 compatibility.

- `utils/atj2137/adfuload/adfuload.c` uploads a stage and can execute a second
  payload. Its memory-read routine is disabled with `#if 0`; it is not a
  working ROM dumper as checked out.
- `utils/hwstub/stub/main.c` implements address-and-length based memory reads,
  writes, and calls, with exception recovery. The target implementation under
  `utils/hwstub/stub/atj213x/` hard-codes an ATJ213x target identity and memory
  map. It requires a running HWStub endpoint. Neither its start-up path nor its
  address map is established for the M30's ATJ2127.
- Installed GNU binutils include MIPS16 assembler/disassembler support, but
  there is no matching MIPS16 C compiler in the current PATH. This is enough
  for small hand-written assembly probes, not for building the Rockbox C
  payloads as MIPS16.
- `utils/atj2137/atjboottool/afi.c` verifies and extracts AFI entries as opaque
  byte ranges. It reports the AFI directory address but does not interpret
  the contents of `NORBREC.BIN`.
- `utils/atj2137/atjboottool/fw.c` extracts firmware members and validates
  their checksums. `fwu.c` handles the outer update-container decryption. The
  source inspected here does not decode the internal NOR boot-record format.

Practical value: reuse Rockbox's existing container and USB protocol
understanding; do not treat its ATJ213x HWStub or disabled ADFU read routine as
a ready M30 ROM-reading solution.

## Installed analysis-tool source audit

The installed Ghidra package includes readable MIPS SLEIGH definitions,
including `mips16.sinc`, plus source archives for the MIPS processor and
emulation framework. These sources clarify what its decoded edges and p-code
probes do. MIPS16 `jal` preserves the current ISA mode, while `jalx` switches
it; the `0xbfc32158` instruction decodes as `jal 0xbfc32001`. That edge cannot
substantiate a call into the even-address MIPS32 entry at `0xbfc32000`. The old
normalized-xref assertion has been removed.

The source also revealed a limit in the original probes:
`MIPSEmulateInstructionStateModifier` handles the `setISAMode` p-code op and
the MIPS ISA context, but `EmulatorHelper.registerCallOtherCallback` overrides
that processor behavior. Its source warns that registering a callback may
bypass processor-module support, and marks `EmulatorHelper` deprecated since
Ghidra 12.1. A new standalone `PcodeEmulator` fixture now exercises a real
MIPS16 `jalx` into MIPS32 and a `jr ra` return to MIPS16; it reaches the MIPS32
body at `0x1008`, returns to MIPS16 at `0x1006`, and verifies the body wrote
`v0=0x1234`. Its assembly source is
[`mips_native_mode_probe.S`](../tools/ghidra/mips_native_mode_probe.S). The
NORBREC reader probe now also uses `PcodeEmulator` and the
native MIPS `setISAMode` userop. It verifies MIPS16 context at both external
edges and requires an odd MIPS16 return address. The NORBREC setup probe has
also been moved to `PcodeEmulator`; it verifies the same native MIPS16 context
and return-address property at all three stubbed ROM edges. This removes the
legacy callback from these NORBREC probes. It does not emulate the unavailable
ROM helpers, and the setup probe does not assert arguments at its first
`0xbfc10a6c` gate.

The QEMU 11.1.3 source archive is already present in the local Homebrew cache
and matches the installed formula's hash, so source review required no network
access. `target/mips/tcg/exception.c` exposes QEMU's general MIPS exception
resume-PC behavior, `target/mips/tcg/translate.c` implements MIPS16 control
flow, and `hw/mips/malta.c` plus `hw/mips/Kconfig` describe the Malta board and
its memory/device map. The QEMU plugin API header documents that exception
discontinuity callbacks run after QEMU prepares the new PC. This supports the
existing passive observer as an independent check of guest-side CP0
observations. QEMU offers generic XBurst CPU models, but its registered
machine is Malta/`none`, not ATJ2127. So QEMU is useful for bounded real-code
probes and exception-vector observation, not full M30 boot, LCD, or DAC
fidelity. A source-level ATJ machine would need evidence-backed memory maps and
peripheral effects before its results would be trustworthy.

### Other locally available analysis tools

The local tool survey is focused on tools that can materially inspect or run
these firmware images; it is not an audit of every application installed on
the host.

- GNU MIPS binutils (`as`, `ld`, `objcopy`, `objdump`) are the useful mixed-mode
  path here: they assemble MIPS probes and disassemble both MIPS32 and MIPS16.
- Capstone's installed `cstool` was tested on the extracted kernel vector. Its
  MIPS32 output independently agrees with the vector bytes, but the installed
  Capstone API exposes MicroMIPS mode and no MIPS16 mode. It is therefore a
  cross-check for MIPS32, not a replacement for binutils/Ghidra on MIPS16.
- CodeGraph is available for source-level navigation in this repository. Its
  index can connect callers and dependencies among our scripts and emulator
  code; it does not disassemble firmware blobs or recover firmware semantics.
  The index was synced after the latest probe commit.
- The targeted command/app/module check found no Binary Ninja, radare2, Rizin,
  Unicorn, angr, `mipsel-linux-gnu-gdb`, or `gdb-multiarch`. The current
  combination already covers the demonstrated needs: Ghidra for MIPS16 p-code,
  binutils for mixed-mode assembly/disassembly, and QEMU for MIPS CPU execution.
  No extra tool was installed because none of the absent candidates currently
  offers a demonstrated gap-filling capability for this work.

### Real extracted exception vector under QEMU/Malta

The real `kernel.drv` exception-vector bytes at file offset `0x380` were also
run at their firmware address `0xbfc08180` on QEMU's Malta `24Kf` CPU. The
reproducible harness is `tools/emu/run_malta_exception_vector_probe.py`. It
builds a temporary 4 MiB Malta BIOS image containing the hash-pinned vector, a
handler stub at `0xbfc06f18`, and slot 9 preseeded with that handler value—the
value independently produced by the extracted `ker_init` constructor probe.
It installs the minimum low-memory TLB pair needed for the vector's stores at
`0x13dcc..0x13dd4`, raises a real `break` exception, and checks the vector's
saved EPC, RA, and Cause's ExcCode bits through Malta's FPGA UART. The image
builder accounts for Malta's MIPS little-endian BIOS word swap. A run against
the 2025-06-26 `kernel.drv` emitted `PASS`.

This is new CPU-level evidence for the extracted vector and its table dispatch
under QEMU. A second harness, `tools/emu/run_malta_constructor_vector_probe.py`,
also executes the hash-pinned MIPS32 constructor and its real MIPS16 setter on
Malta. It redirects the setter's table-base literal from BIOS to RAM and the
vector's table-base instruction to the same RAM table. At the audited boundary
`0xbfc32c5c`, the harness redirects control into its slot checks; it verifies
slots 0..13 and untouched sentinels at 14 and 31, then raises ExcCode 9 and
checks the real vector's saved EPC/RA/Cause. Runs passed on QEMU `24Kf` and
`34Kf`. The boundary instrumentation replaces the first two instructions at
that point, and the fixed-ROM handler remains a stub. These probes do not
establish M30 bootloader invocation, ATJ2127 peripheral behavior, or device
fidelity.

## NOR boot-record observation

The AFI archives already extracted from dated M30 updates contain a
`NORBREC.BIN` member of size `0x28a00`. Its SHA-256 is
`8b11f25209e6a79474559b4b48d3a9d7f29d5f07633d234fe0eada9b8cd4e93d` in the
2022-09-14 and 2025-06-26 images, and the corpus note records the same hash for
the other compared releases.

The outer AFI directory entry was also checked directly by decrypting the
already-local 2025-06-26 HEX with Rockbox `atjboottool` in ATJ2127 mode, then
unpacking its AFI. The `NORBREC.BIN` entry has type `B`, AFI address `0`, file
offset `0x34c00`, size `0x28a00`, and additive checksum `0x96b22f89`; the AFI
checksum verifier passes. The extracted member is byte-identical to the
existing local copy (SHA-256 above). Because the AFI address is zero, it does
not explain the internal word `0xbfc32000` at member offset `0x1c` or prove a
runtime load address. It only confirms the outer member boundary and checksum.
This prevents treating the internal address candidate as an AFI directory
load-address field.

The local tool invocation was performed with output confined to a unique
temporary directory; no image was downloaded and no device or card was
written. To repeat on another already-local image, run `atjboottool
--fwu --atj2127 -o <temporary-dir>/decoded.afi <input.HEX>`, then run
`atjboottool --afi -o <temporary-dir>/members/ <temporary-dir>/decoded.afi`
after creating the empty `members/` directory. The AFI output reports the
directory entry and verifies its checksum before extracting members.

### NORMBREC bootstrap and fixed-address function-pointer slots

The same AFI contains a second, much smaller bootstrap member,
`NORMBREC.BIN`: type `M`, `Addr=0`, offset `0x8c00`, size `0x200`, checksum
`0xa897adc5`. Its community and stock copies have the same SHA-256
`dacb549733c6af1727ed2dd8efe091ff6386ef3a111329f5407475ee620c5ace`.
The locally available Rockbox `atjboottool` AFI parser only reports the type,
address, offset, size and checksum, then passes the member bytes to an
extraction callback; its recognized type constants do not include `M`, and
the entry loop does not dispatch on type. The local `actions_flash` sources
also do not connect AFI type `M` to a load/execute command. So `M` versus `B`
does not establish either member's runtime address or execution semantics.
Because its AFI address is zero, its runtime mapping is not supplied by the
AFI. The strongest local working mapping is `NORMBREC` at `0xbfc08000`: the
MIPS32 jump at member offset `0x10` targets `0xbfc08018`, where the next
instructions in this same member set `sp=0x9fc0c000`; the following `jalx`
targets `0xbfc08029`, the MIPS16 routine at member offset `0x28`. This is a
coherent in-member MIPS32-to-MIPS16 path, but remains a mapping hypothesis
because the AFI `Addr` is zero and no boot trace shows this member being
loaded or executed.

The new bounded probe `tools/ghidra/run_normboot_path_probe.sh` executes the
hash-pinned 512-byte member under this candidate mapping. Ghidra p-code takes
the real jump at `0xbfc08010` to `0xbfc08018`, executes the two stack writes
to `0x9fc0c000`, then follows the real `jalx` to `0xbfc08028` with
`ISA_MODE=1`. The probe seeds `RELP=1` as a MIPS16e-capable CPU context; it
does not establish the device's reset context or that the device loads this
member at `0xbfc08000`. The two package components' shared address window
therefore remains a staged-reuse hypothesis.

`kernel.drv` also has a YQHX segment at `0xbfc08000` (file offset `0x200`,
length `0xb00`). Its bytes differ from `NORMBREC`'s bytes at that address.
This is evidence that two package components target the same virtual window,
consistent with staged reuse/overlay, but it does not show that NORMBREC's
branch executes `kernel.drv` contents. An alternative `NORMBREC` base of
`0xbfc00000` would make the absolute jump at `+0x10` land at `0xbfc08018` in
the kernel segment, but would bypass the in-member stack setup and continue in
MIPS32 mode into bytes decoded as MIPS16. There is no runtime evidence for
that interpretation, so it is not the working mapping.

The MIPS16 routine at candidate address `0xbfc08028` loads function pointers
indirectly through fixed addresses `0xbfc002a8`, `0xbfc002a0`, `0xbfc00294`,
and `0xbfc002a4`. For example, the first call through the slot at
`0xbfc002a8` passes `(a0 = byte[0xbfc08003] << 9, a1 = 0x1800,
a2 = 0xbfc10000)`. A second call through the same slot is made when the
block-size-derived value `(u16[0xbfc10004] << 9)` is at least `0x1801`; its
arguments are `(a0 = (byte[0xbfc08003] << 9) + 0x1800,
a1 = (u16[0xbfc10004] << 9) - 0x1800, a2 = 0xbfc11800)`. The first call's
return is combined with the second call's result later; it does not trigger
the second call. Further calls pass values derived from the same fields
through the other pointer slots. This is direct disassembly of the caller;
the slot contents, service meanings, and effects are unavailable, and the
addresses are not independently proven to map ROM.

The bounded `tools/ghidra/run_normboot_service_path_probe.sh` executes this
actual MIPS16e routine in Ghidra p-code with all four pointer slots and their
targets supplied as synthetic fixtures. It checks three input/return cases;
the byte at `0xbfc08003` is zero in all of them:

- With `u16[0xbfc10004]=4` and zero callback returns, the path executes 58
  real MIPS instructions (excluding three intercepted synthetic callbacks) and
  calls `0xbfc002a8 → 0xbfc00294 → 0xbfc002a4`. The captured tuples are
  `(0,0x1800,0xbfc10000,0)`,
  `(0xbfc10000,0x800,0xbfc10000,0)`, and
  `(0xbfc10100,0x00703041,0,0)`. The `0xbfc002a0` error slot is skipped.
- With `u16[0xbfc10004]=13`, the scaled length is `0x1a00`, so the routine
  makes two `0xbfc002a8` calls: `(0,0x1800,0xbfc10000,0)` then
  `(0x1800,0x200,0xbfc11800,0)`. It then calls `0xbfc00294` with
  `(0xbfc10000,0x1a00,0xbfc11800,0)`, followed by `0xbfc002a4`.
- When the first `0xbfc002a8` callback returns synthetic `1`, the routine
  calls `0xbfc002a0` with the first call's same tuple, then continues to
  `0xbfc00294` and `0xbfc002a4` in this fixture.

The large and nonzero-return fixtures execute 78 and 61 real MIPS
instructions, respectively; the step bound separately counts loop iterations
including callback interceptions.

The third tuple above includes the deliberately seeded pointer value from slot
`0xbfc002a0`; it is evidence of caller dataflow under this fixture, not the
meaning of that argument or callback. The probe checks call order and all four
arguments. Callback destinations and return effects are synthetic, so this
establishes the real routine's conditional control/data flow only under these
inputs; it does not identify the services or establish boot behavior.
Reproduce with
`bash tools/ghidra/run_normboot_service_path_probe.sh <path-to-NORMBREC.BIN>`.
The member is a local extracted input and is not present in a clean checkout.

There is an independent callsite to one of these same slots in `NORFWSC.BIN`.
With the member mapped at its AFI address `0xbfc1e000`, the MIPS16 code at
`0xbfc1e660` loads `0xbfc002a0` from its literal at `0xbfc1e67c`, dereferences
that slot, and calls the loaded pointer at `0xbfc1e66a` when the byte read
through the incoming `a1` is nonzero. This confirms that two boot-stage
members use the same pointer slot; it does not reveal the slot's contents or
function semantics. Reproduce with
`mipsel-linux-gnu-objdump -D -b binary -m mips:16 -EL --adjust-vma=0xbfc1e000
--start-address=0xbfc1e660 --stop-address=0xbfc1e680` on the hash-pinned
`NORFWSC.BIN`.

The same numeric address also appears in `udisk.ap`. Its literal at file offset
`0x102c` maps to `0x40014f2c` in the bank loaded from file offset `0xd00` at
`0x40014c00`. Ghidra finds an xref to the literal from instruction
`0x40014ed2` inside `FUN_40014ecc`. Reading the instructions carefully shows
that the AP loads the word *at* `0xbfc002a0`, stores that loaded value in RAM
global `0x9fc1564c`, makes calls through `0xbfc1640d` with `(10,7,0,5)` and
`0xbfc16401` with `(3,0,0,0x1d)`, updates MMIO and several globals, then calls
through the RAM global with no arguments. So `0xbfc002a0` is also used as a
fixed-ROM pointer slot here; the earlier direct-code-pointer interpretation
was wrong. The literal and file slice `0xfcc..0x1050` (function plus nearby
pointer data) are byte-identical in the pinned community and stock `udisk.ap`
variants.

The p-code probe executes the real stock AP instructions with the word at
`0xbfc002a0` seeded to synthetic MIPS16 pointer `0x00704001`, both resident
calls intercepted with return value zero, and MMIO word `0xc0020000` seeded
to `0xa5a5005f`. It reaches the synthetic target after 42 AP instructions
(44 probe iterations, including the two intercepted helper calls),
leaves `0x00704001` in global `0x9fc1564c`, and changes the MMIO word to
`0xa5a5006a`. The real target body is not executed; the ROM slot's actual
contents and meaning remain unknown. This p-code result plus the instruction
trace resolves the apparent direct-versus-dereferenced discrepancy with
NORMBREC/NORFWSC: all inspected callsites dereference the fixed address to get
a pointer. Reproduce static cross-variant checks with
`bash tools/ghidra/run_normboot_udisk_pointer_probe.sh <community-udisk.ap> <stock-udisk.ap>`
and the dynamic caller boundary with
`bash tools/ghidra/run_udisk_fixed_callback_probe.sh <stock-udisk.ap>`.
The corpus-wide raw-value lead was found with
`python3 tools/emu/dac_caller_scan.py <extracted-analysis-root> --value 0xbfc002a0 --value 0xbfc002a8 --value 0xbfc00294 --value 0xbfc002a4`.
That byte search includes duplicated package copies and is a lead generator,
not a caller list; Ghidra xref/decompilation provides the code-use evidence.

This bootstrap does not contain the literal `0xbfc32000` and the inspected
instructions do not read `NORBREC.BIN`'s `+0x1c` field. It therefore does not
establish how the larger NOR record is loaded or whether that field is used
for a handoff. The next high-value steps are to find loader evidence for the
shared `0xbfc08000` window and recover the functions behind
`0xbfc00294..0xbfc002a8` before assigning meaning to the MIPS16 calls. The
static disassembly is reproducible with
`mipsel-linux-gnu-objdump -D -b binary -m mips:isa32r2 -EL --adjust-vma=0xbfc08000`
for the MIPS32 prefix and `mipsel-linux-gnu-objdump -D -b binary -m mips:16
-EL --adjust-vma=0xbfc08000 --start-address=0xbfc08028` for the MIPS16 body;
the verified input is the local `analysis/community/afi/NORMBREC.BIN` member.
Run the bounded p-code check with
`bash tools/ghidra/run_normboot_path_probe.sh analysis/community/afi/NORMBREC.BIN`.

The same AFI directory records `Addr=0xbfc1e000` for both `NORHWSC.BIN`
(type `H`, file offset `0x5800`, size `0x800`) and `NORFWSC.BIN` (type `F`,
file offset `0x6000`, size `0x2a00`). Their verified AFI checksums are
`0xa0bfabaa` and `0x80578010`, respectively. `NORFWSC.BIN` has an all-zero
`0x200`-byte prefix; MIPS32 code begins at file offset `0x200`. If the AFI
`Addr` is used as its load base, the in-image MIPS16 calls resolve to addresses
such as `0xbfc1e681` and `0xbfc1f099`.

This aligns with the staged execution addresses in the local
[`actions_flash` reference](https://github.com/kilicdev/actions_flash/blob/007efc8/README.md#repeating-the-factory-flashing-sequence-atj2127-reference),
checked at commit `007efc8`: it loads `nandhwsc.bin` at `0xbfc1e000` and runs
that address, then loads `fwsc.bin` at `0xbfc1e000` and runs
`0xbfc1e200`. The names differ (`NORHWSC`/`NORFWSC` versus `nandhwsc`/`fwsc`),
and no binary hash match was established. This supports interpreting the AFI
`Addr` as the load base and `NORFWSC` offset `0x200` as a candidate execution
entry, but does not prove that the payloads are identical or that this exact
sequence ran on the M30. The reference's later `write_flash` commands were not
run.

### NORFWSC ADFU result descriptor

The local [`actions_flash` ADFU implementation](https://github.com/kilicdev/actions_flash/blob/007efc8/payload/adfus.c)
defines an execution result as `{ addr, size }`: command `0x21` stores the
function's returned pointer, command `0x22` reads its `size`, and command
`0x23` sends bytes from `addr`. Its host helper in
[`actions_dump.c`](https://github.com/kilicdev/actions_flash/blob/007efc8/actions_dump.c)
uses that interface for `exec_ret`.

At candidate NORFWSC address `0xbfc1e2a8`, a Ghidra p-code probe executes the
real conditional return tail with the helper result and saved stack frame
seeded. The tested nonzero-result branch stores data pointer `0x9fc24ca4` at
`0x9fc24cfc`, writes size `0x58` at `0x9fc24d00`, returns `0x9fc24cfc` in
`v0`, and reaches the seeded return address. This matches the
`actions_flash` result-descriptor shape. Reproduce with
`GHIDRA=/path/to/analyzeHeadless bash tools/ghidra/run_nor_fwsc_probe.sh
/path/to/NORFWSC.BIN`.

This is a bounded code-path execution, not a run of the complete NORFWSC entry:
the prior helper bodies and the zero-result branch are not modeled. The AFI
entry does not explicitly declare `0xbfc1e200` as an entry point, so its
relationship to the public recipe remains a high-confidence address/layout
correlation rather than proof of M30 runtime invocation. The recipe requests
512 bytes with fixed `exec_ret ... 512`; in the tested NORFWSC branch the
descriptor advertises `0x58` bytes. The host implementation uses the explicit
512-byte request without first querying the descriptor size, so the request
length alone must not be interpreted as NORFWSC's reported result size.

Observed bytes and disassembly:

- At file offset `0x1c`, the little-endian word is `0xbfc32000`. Under the
  candidate mapping, bytes at file offset `0x100` land at `0xbfc32000` and
  disassemble as a valid MIPS32 entry prologue. This makes `+0x1c` a
  **candidate internal entry-pointer field**. The mapping was inferred from
  that word, the AFI `Addr` is zero, and no loader is available to show that it
  consumes the field; treat the interpretation as a hypothesis, not a decoded
  header contract.
- The candidate MIPS32 prologue sets SP to `0x9fc3fe00`, moves incoming
  arguments, then calls `0xbfc10191` at `0xbfc32010`.
- Header word `+0x24` is `0xbfc08d80`. The local extracted corpus gives no
  corroborating use of this value outside copies of the same NORBREC member;
  its role is unresolved. The `0xbfc32000` word also appears at member offsets
  `0x30c` and `0xb40`, but those raw occurrences do not show that code reads
  the header field. All available NORBREC copies are byte-identical, so these
  repetitions provide no independent validation of either header value.
- The same bytes are not MIPS16 instructions. Later regions do contain
  readable MIPS16 code; disassembling this mixed-mode member with `-m mips:16`
  is substantially more useful than treating the whole file as MIPS32.
- At file offset `0xa2c` (candidate runtime address `0xbfc3292c` under the
  header's `0xbfc32000` mapping), a MIPS16 routine computes a 512-byte block
  count, calls an indirect routine obtained through `0xbfc02600`, then calls
  the fixed-ROM MIPS16 entry `0xbfc108d9` with `(original a0, 0x9fc27000,
  original a2)`. Ghidra normalizes this MIPS16 target to `0xbfc108d8`. This
  establishes the call shape, not the ROM helper's semantics.
- At candidate address `0xbfc3296c`, a MIPS16 routine reads a directory block
  by passing a sector derived from the word at `0xbfc10138`, destination
  `0x9fc12000`, and count `1` to an indirect helper. It then scans 32-byte
  records in that buffer for `KER_TEXT.BIN`, `KER_DATA.BIN`, `KER_INIT.BIN`,
  and `KER_XSYS.BIN`, and passes matching record fields at offsets `+0x10` and
  `+0x14` to a fixed-ROM helper. This ties the NOR record to selection of the
  extracted kernel components; exact directory and helper semantics remain
  partly unresolved.
- At file offset `0xb48` (candidate runtime address `0xbfc32a48`), another
  MIPS16 routine populates the first 16 bytes of a structure at `0xbfc10124`, then calls
  `0xbfc108d9(0x9fc12000, 0xbfc10124, 0x14)` and
  `0xbfc108d9(0x9fc12014, 0xbfc10138, 0x20)`. The two calls are consistent
  with copying/building a small boot structure in the `0x9fc12000` region,
  but a copy interpretation remains a hypothesis until the ROM helper is
  identified. This is a concrete lead for explaining the later kernel's
  reads from the same address window.
- A separate byte/disassembly cross-check confirms that the reader's literal
  at file offset `0xa64` and the setup routine's literal at `0xb9c` are used
  as bases for pointer loads at `0xbfc02600`. Their use in these two call
  paths is visible in the instructions; the fixed-ROM table contents and the
  runtime value selected by other references remain unknown.
- The code does not match the start of the separately extracted, hash-pinned
  `ker_init.bin`, despite both candidate mappings using `0xbfc32000`. Their
  first 32 bytes differ. The NOR record's early wrapper and the firmware's
  later `ker_init` segment may occupy the same address at different boot
  stages; the actual handoff and mapping are still unverified.

The central MIPS16 call sequence can be reproduced with the installed GNU
binutils (the input path is local to the analysis host):

```sh
mipsel-linux-gnu-objdump -D -b binary -m mips:16 -EL \
  --adjust-vma=0xbfc31f00 \
  --start-address=0xbfc3292c --stop-address=0xbfc32ac0 \
  /path/to/NORBREC.BIN
```

The `0xbfc31f00` adjustment accounts for the observed code at file offset
`0x100` mapping to `0xbfc32000`. It is a candidate mapping derived from the
record, not yet independently verified runtime state. The fixed-ROM bodies at
`0xbfc10191`, `0xbfc108d9`, and the indirect target reached through
`0xbfc02600` remain unavailable; NORBREC's complete header and loader ABI are
not yet decoded.

The Ghidra mapping and decompilation are reproducible with
[`run_norboot_probe.sh`](../tools/ghidra/run_norboot_probe.sh). It pins the
member hash, maps the first `0x18` bytes of the code entry as MIPS32, marks the
observed body from file offset `0x158` as MIPS16, and checks the decompiled
call shapes for `FUN_bfc3292c` and `FUN_bfc32a48`. The entry cross-reference
is deliberately not used as proof of a call into
the MIPS32 entry: binutils decodes the instruction at `0xbfc32158` as a
MIPS16 `jal 0xbfc32001`, while the candidate entry is mapped as MIPS32 at
`0xbfc32000`. Ghidra's normalized even-address xref cannot reconcile that
ISA-mode conflict. No direct references to the directory/setup routines have
been established; that does not rule out ROM or function-pointer dispatch.
Ghidra also warns that decompiling the entry stub across its MIPS32-to-MIPS16
boundary is unresolved. The script runs the extracted `0xbfc32a48` MIPS16
routine in Ghidra p-code with synthetic RAM/ROM state. It records three
external calls: one to `0xbfc10a6c` (its register arguments are not asserted),
followed by two calls to `0xbfc108d8` with arguments
`(0x9fc12000, 0xbfc10124, 0x14)` and
`(0x9fc12014, 0xbfc10138, 0x20)`. It verifies the caller's structure writes
at `0xbfc10124` and metadata stores at `0x9fc12200`/`0x9fc12204`. The ROM
calls return synthetic zero; the probe does not emulate the helper's
copy/transform semantics, so it proves caller behavior only. It also executes
the `0xbfc3292c` reader wrapper with synthetic arguments using the native MIPS
p-code userop. It observes a block-reader call
`(sector=25, scratch=0x9fc27000, count=4)` for a `0x601`-byte input, followed
by the ROM helper with `(0x9fc13000, 0x9fc27000, 0x601)`. The indirect reader
pointer carries the MIPS16 low-bit tag; the native emulator switches mode and
the probe checks the MIPS16 context and tagged return address at each stub.
This validates the wrapper's round-up to four 512-byte blocks for this
non-aligned fixture and its outgoing arguments; the block reader and ROM
helper are synthetic stubs, so their semantics are not modeled. The successful
script prints these observed call edges. It executes no firmware on the player
and assigns no behavior to missing ROM functions.

The candidate MIPS16 bridge at `0xbfc32058` is now also stepped in the same
native p-code run by `RunNorBootCallbackBridgeProbe.java`. With synthetic
external state (`*(0xbfc10118)=0x20`, `*(0xbfc1011c)=0x9fc11000`, and a
halfword value `7` at `0x9fc1100c`), the real code loads a MIPS16-tagged
callback from synthetic slot `0xbfc02608`. For `a2=0x28` (40), it calls that
target with `(a0=0x5400, a1=0x9fc13000, a2=0x5000)` and returns through
`ra=0xbfc32085`; for `a2=0x29` (41), it returns without the indirect call.
The target body is stubbed. This verifies the code's branch and outgoing ABI
under the stated fixture, not the actual fixed-ROM values, callback identity,
parent caller, or boot-time execution.

A fresh xref pass against the hash-matching local community `NORBREC.BIN`
narrows the caller search. After `MapNorBoot.java` mapped the mixed-ISA image,
Ghidra found no code reference to `0xbfc32058`; a scan of mapped image words
also found no literal `0xbfc32058` or tagged pointer `0xbfc32059`. The bridge
is not directly called or named by a pointer literal inside this member. This
does not rule out a computed address, fixed-ROM callback registration, or an
external loader call.

The same xref pass found reads from external slot `0xbfc02608` in
`FUN_bfc322a8` (`0xbfc322b2`), `FUN_bfc32388` (`0xbfc32400`), and
`FUN_bfc32a58` (`0xbfc32a66`). The first two functions load that slot's value
for an indirect call; the setup path reads through it while constructing
helper arguments. The slot contents are absent from NORBREC, so these
references do not identify its target or connect it to the `0xbfc32058`
bridge. Ghidra reports additional reads from table base `0xbfc02600` in the
reader/setup and nearby record-processing functions, but no direct bridge
caller emerged. Reproduce with `MapNorBoot.java` followed by `DumpRefsTo.java`
(`0xbfc32058,0xbfc02600,0xbfc02608`), then `FindPointerRefs.java`
(`0xbfc32000,0xbfc32001,0xbfc32058,0xbfc32059`) on a hash-matching member.

This narrows the NORBREC callback ABI, but does not identify the bridge's actual
fixed-ROM state or prove that it is called during boot. It also does **not**
identify NORBREC as the active ROM bootstrap, nor prove that `0xbfc32000` is the
runtime address at the relevant boot stage. The repeated NORBREC hash means
decompiling each dated copy would add no independent code evidence.

## Suggested next work

1. The hash-matched NORBREC has no internal direct caller or pointer to
   `0xbfc32058`; recover its external entry/callback registration from the
   fixed-ROM or boot-stage caller, and establish whether the `0x9fc12000`
   setup runs before firmware `ker_init`.
2. Identify the internal NORBREC header's data and entry fields. The outer AFI
   record is now known but supplies no load address; the nine local dated
   NORBREC copies are byte-identical, so they cannot independently validate
   candidate field meanings.
3. Compare the `NORHWSC`/`NORFWSC` member contents with the public staged
   execution recipe at the byte/function level. The load and entry addresses
   align, but the renamed payloads have not been hash-matched and the M30
   invocation remains unobserved.
4. Keep all analysis offline. Do not execute guessed payloads on the player.
