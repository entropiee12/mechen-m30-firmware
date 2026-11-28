# Kernel exception dispatch matrix

The extracted `ker_init.bin` constructor at `0xbfc32bc8` initializes the real
exception table at `0x9fc1a9a4`; the extracted `kernel.drv` vector at
`0xbfc08180` indexes it with `Cause & 0x7c`. I extended the existing composed
Ghidra p-code probe to execute that constructor once, then exercise vector
dispatch for every exception code 0–13 with the corresponding cause bits and
unrelated high/low cause bits set.

The table values and observed dispatch targets are:

| ExcCode | Handler pointer | ISA tag | Frequency in table |
|---:|---:|---|---|
| 0 | `0xbfc19624` | MIPS32 | one slot |
| 1–8 | `0xbfc19a75` | MIPS16 | default handler |
| 9 | `0xbfc06f18` | MIPS32 | one slot |
| 10 | `0xbfc195d4` | MIPS32 | one slot |
| 11–13 | `0xbfc19a75` | MIPS16 | default handler |

For each code, the probe checks the constructor-produced slot against this
table, runs the vector until its selected handler address, and verifies the
saved EPC, RA, and full Cause word at `0x13dcc`, `0x13dd0`, and `0x13dd4`.
The first failed matrix attempt exposed the MIPS16 mode requirement for the
default handler: its tagged pointer is odd (`0xbfc19a75`) while the emulator's
canonical PC is `0xbfc19a74`. The probe now sets the matching Ghidra ISA context
and compares the canonical PC while retaining the tagged pointer in its
reported matrix.

Reproduce against the local 2025-06-26 extracted members:

```sh
bash tools/ghidra/run_kernel_initialized_exception_probe.sh \
  /path/to/hash-matching/kernel.drv \
  /path/to/hash-matching/ker_init.bin
```

The observed run passed all 14 cases. The kernel and init members have the
same SHA-256 values in the eight locally checked dated corpora:
`kernel.drv` `58f9d0c6…05d8bd6` and `ker_init.bin`
`f4c62848…47492ec8`. This establishes the matrix for those byte-identical
members, not for every possible M30 firmware revision.

**Limits:** this is Ghidra p-code execution of extracted code with synthetic
CP0 inputs and RAM. It observes dispatch only up to each selected handler;
none of the handlers, fixed ROM, or real exception entry conditions were
executed. It identifies routing, not handler semantics, hardware exception
behavior, or boot-time invocation.

## Extracted alternate exception-stack selector

The neighboring MIPS32 code at `0xbfc340a4` reads a stack-table index from
`0x9fc1a495`, loads the corresponding word from `0xbfc19974 + index*4`, and
compares it with `sp & 0x3ffff`. A hash-pinned Ghidra p-code probe executes the
real instructions with RAM-backed globals/table and stops at the fixed-ROM
boundary `0xbfc05f90`.

With a synthetic table word of `0x12000`, masked `sp=0x11fff` and equality
`sp=0x12000` both take the alternate-stack path; `sp=0x12001` takes the direct
path. This confirms the strict comparison: `table_word < masked_sp` selects
the direct path, while equality selects alternate-stack setup. In that setup
the code decrements the next-stack pointer at `0x9fc1a8a0` by 184 bytes, moves
`sp` to the previous pointer minus four, stores the old `sp` there, and
increments bytes at `0x9fc1a493` and `0xbfc19930`. The table index byte remains
unchanged in these profiles.

The same selector was then executed natively on QEMU Malta with the
hash-matching `ker_xsys.bin`. The BIOS fixture keeps the real table read at
`0xbfc19974` and substitutes only the six `lui` operands that address
writable globals (`0x9fc1a495`, `0x9fc1a493`, `0x9fc1a8a0`, and
`0xbfc19930`) so those accesses land in Malta RAM. It places a return-only
stub at the fixed-ROM boundary `0xbfc05f90`. QEMU 24Kf and 34Kf both passed
four cases: at index 0, below and equal use alternate-stack setup while above
takes the direct path; at index 1, a different table entry selects the direct
path. For the index-1 discriminator, table[0]=`0x12000`, table[1]=`0x10101`,
and masked SP=`0x11000`; reading entry 0 or an unaligned table word by mistake
would switch stacks. The asymmetric bytes also make the tested endianness
observable through the branch outcome.
Assertions cover final SP, saved old SP, updated stack-top pointer, both
counters, unchanged index, and the direct-path canary/state.

This is real extracted MIPS32 execution under compatible generic MIPS CPUs,
with synthetic RAM globals/table threshold and an instrumented ROM boundary.
It does not execute the unavailable exception handler or establish Malta as
an ATJ2127 model, real boot-time global values, caller flow, or hardware
effects. The harness polls Malta's 16550 THRE status before each serial result
byte. Reproduce with:

```sh
python3 tools/emu/run_malta_xsys_stack_probe.py /path/to/hash-matching/ker_xsys.bin
```

Reproduce with:

```sh
bash tools/ghidra/run_ker_xsys_alternate_stack_probe.sh \
  /path/to/hash-matching/ker_xsys.bin
```

This establishes the extracted selector's branch and memory effects for the
tested synthetic table values. It does not identify the table's real contents,
the meanings of the two counters, how callers reach this code, the writable
hardware mapping of `0xbfc19930`, or the fixed-ROM handler's behavior.

## Extracted `ker_xsys` exception-entry frame stores on Malta

QEMU Malta also executes the separate real MIPS32 entry at `0xbfc34074`. The
harness maps virtual pages `0x12000/0x13000` to Malta RAM, seeds five words at
`0x13dcc..0x13ddc` with a canary, sets CP0 EPC to `0x80001234`, and calls the
entry directly. The code stores that EPC at `0x13dd8`, stores its call-return
RA at `0x13ddc`, and jumps to a return-only stub at the fixed-ROM boundary
`0xbfc06d6c`. Both QEMU 24Kf and 34Kf pass: the first three seeded words remain
untouched and the final two contain the expected EPC/RA. Reproduce with the
same Malta probe command above.

This executes the extracted frame-producing instructions, but it does not
raise a hardware exception or reproduce the natural caller, Cause, kernel
vector sequence, or fixed-ROM handler. In particular, it does not establish
that this entry leads to `FUN_bfc344f4`; that call edge remains unresolved.

## Malta QEMU vector-to-entry composition with a synthetic slot-9 bridge

The Malta exception-vector probe now has an optional `--xsys` mode. It raises
a real QEMU `break 0`, executes the hash-pinned `kernel.drv` vector at
`0xbfc08180`, and reads slot 9 from the BIOS-mapped exception table. The
builder seeds that slot with the constructor-observed value `0xbfc06f18`.
At that address, a synthetic direct-jump bridge enters the hash-pinned
`ker_xsys.bin` prologue at `0xbfc34074`; this replaces the unavailable fixed
ROM handler for the experiment and is not a recovered call edge.

The vector writes EPC, interrupted `$ra`, and Cause to `0x13dcc..0x13dd4`.
The real xsys entry then appends CP0 EPC and `$ra` at `0x13dd8/0x13ddc` and
jumps to a harness checkpoint at `0xbfc06d6c`. Since both the bridge and the
vector transfer use `j`/`jr` without linking, the seeded interrupted `$ra`
remains `0x12345678`; the probe asserts it in both RA frame slots. It also
checks the EPC values and canaries at `0x13dc8` and `0x13de0`. QEMU 24Kf and
34Kf pass. Reproduce with:

```sh
python3 tools/emu/run_malta_exception_vector_probe.py \
  /path/to/hash-matching/kernel.drv \
  --xsys /path/to/hash-matching/ker_xsys.bin
```

This composes the extracted vector and entry instructions under a real QEMU
exception event. The slot-9 table value is seeded, the ROM bridge and final
checkpoint are synthetic, and the firmware's actual handler-to-entry
relationship, constructor invocation, complete device boot, and ATJ2127
hardware behavior remain unknown.

## Extracted five-word exception-frame consumer

The hash-pinned `ker_xsys.bin` also contains MIPS16 function
`FUN_bfc344f4`. Its literal at `0xbfc34554` is `0x00013dcc`; the following
literal at `0xbfc34558` is the tagged callback pointer `0xbfc04841`. A
read-only Ghidra decompile shows a five-iteration loop that calls that pointer
with a descriptor at `0xbfc3454c`, the next 32-bit word beginning at
`0x13dcc`, and argument `2`. The descriptor bytes are `exc:`. The five reads
therefore cover `0x13dcc..0x13ddc`: the three words saved by the kernel
exception vector and the EPC/RA pair saved by the `ker_xsys` entry. The
function checks bit 1 of the word at `0x9fc1aa5c`: clear writes `0x5d` and set
writes `0x69` to MMIO address `0xc012001c`. The kernel-init trace independently
shows a write of `1` to that address, but does not identify the peripheral
function. These byte values' hardware effect is still unknown.

The real MIPS16 function was run in Ghidra p-code with five distinct seeded
words and a synthetic callback stub replacing the unavailable ROM callback.
The probe observed five calls in order, each with the expected frame word,
descriptor, and width argument, and verified canaries immediately before and
after the frame. Two RAM-backed status/MMIO profiles then confirmed the real
code writes `0x5d` when bit 1 is clear and `0x69` when it is set. This models
the address as RAM; it does not simulate the peripheral. Reproduce with:

```sh
bash tools/ghidra/run_ker_xsys_exception_frame_probe.sh \
  /path/to/hash-matching/ker_xsys.bin
```

This establishes the extracted consumer's frame-reading and callback ABI,
plus its conditional values written to the MMIO address, under synthetic
callback and RAM-backed MMIO. It does **not** establish that the missing fixed
ROM handler calls this function or that it is the ExcCode 9 path. A
direct-pointer scan of the locally extracted members found no reference to
`0xbfc344f4`; an indirect ROM call remains possible.

## Shared ROM diagnostic-callback hypothesis

The same hash-pinned member contains MIPS16 function `FUN_bfc3416c`. Its
guard-failure branch loads the same callback pointer literal, `0xbfc04841`,
and calls it with the descriptors `sp ` and `stk overflow`, each paired with
a 32-bit value and argument `2`. It then calls the default handler at
`0xbfc19a75`. The runner decompiles this function and checks both call shapes;
the byte strings and callback pointer are also checked against the pinned
input. `FUN_bfc344f4` uses the same callback with descriptor `exc:` and five
words from the exception frame.

These independent call shapes make a diagnostic formatting/emission helper
the strongest current interpretation for the ROM callback at `0xbfc04840`.
Its ROM body is unavailable, so the exact formatting contract and output
destination remain unknown.

The hash-pinned p-code probe now also executes the actual extracted
`FUN_bfc3416c` instructions through its guard-failure branch. It seeds the
function's low-RAM lookup table and state flag to force that branch, and uses
synthetic MIPS16/MIPS32 stubs for unavailable fixed-ROM services: the stack
classification call, a returned stack value, and the diagnostic callback. It
observes two callback entries with `(descriptor, value, 2)`:
`(0xbfc341d0, 0x200, 2)` and `(0xbfc341d4, 0x55, 2)`, then reaches the canonical
default-handler PC `0xbfc19a74`. The guard branch also clears bit 8 in the
seeded word at `0x9fc1a9e8`.

This confirms the extracted branch's control flow, argument preparation, and
state write for this synthetic profile. The ROM stubs supply the values that
select the branch; the test does not establish their real return values, run
the diagnostic ROM body, or prove that firmware naturally reaches this
branch. The full callback-output and fixed-ROM semantics therefore remain
unknown.

The probe isolates the two guard conditions. With a matching guard word, a
synthetic stack distance of `0x93` takes the failure path, while `0x94` returns
normally without diagnostics and preserves the state word, confirming the
extracted strict `< 0x94` comparison. With distance `0x180` but a mismatched
guard word, the failure path also runs. These controlled profiles verify both
failure causes and the boundary in the extracted instructions; the ROM
service's real return value and natural input state remain unverified.

The exact `ker_xsys.bin` SHA-256 above is shared by the locally available
dated extracted corpora from 2022-09-14 through 2025-06-26 that contain this
member. A raw little-endian literal scan across the local extracted corpus found
neither tagged nor untagged entry pointer for these functions. This is bounded
negative evidence: a computed address, resident dispatch, or missing fixed-ROM
call can still reach either function. It means the local extracted code does
not currently provide the missing caller edge.
