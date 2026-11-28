# `mmm_bp.al` decoder context initialization boundary

## Result

The recovered module has a concrete internal entry/initializer chain, but the
available stock and community images do not identify an external caller that
writes the decoder's indirect status pointer. The strongest candidate is an
initialization call that supplies a memory range containing the slot to an
unresolved resident service. Its effect is not proven, so it cannot yet be
called a status-pointer writer.

## Static call/dispatch evidence

The stock and community `fw/mmm_bp.al` members are byte-identical
(SHA-256 `ab5d98b578406c4e04fb7b546317fbec0d36ce4a7c139ef0922b06bb4b753c5b`).
The AP-style header entry at file offset `0x40` is odd MIPS16 pointer
`0xbfc218a9`, aligned entry `FUN_bfc218a8`. In that entry, the operation
dispatcher pointer at `0xbfc21918` is `0x680a1ed5`, aligned
`FUN_680a1ed4`. The dispatcher's operation-0 case makes a computed call through
`0x680a200c`, whose literal is `0x68061c01`, aligned `FUN_68061c00`.
Ghidra's reference report records the computed-call edge from `0x680a1fd6` to
`0x68061c00`.

`FUN_68061c00` uses fixed RAM address `0x9fc21b08` as its context block. Before
its explicit field assignments, it calls the unresolved stub pointer
`0xbfc21a39` with `(a0=0x9fc21b08, a1=0, a2=0xec, a3=1)`. The decoder worker
`FUN_bfc216a8` reads the status pointer from absolute address `0x9fc21bd0`
(`0x9fc21b00 + 0xd0`). That slot is within the range supplied by the earlier
call: it is `0xc8` bytes from `0x9fc21b08`, inside the `0xec`-byte extent.

This is direct evidence for the call tuple and range overlap. Treating the
service call as a zero-fill is an inference from `(buffer, 0, length, mode)`;
the service body is unavailable, so neither zeroing nor ownership of the
status slot is established. Later in `FUN_68061c00`, a registration record
containing the context pointer is passed through the separate stub pointer at
`0x68061d20` (literal `0xbfc21a3d`) with operation values `0x33` and `0x4a`.
These are not calls via the `0xbfc21a39` pointer discussed here. Their meanings
and any side effects on `0x9fc21bd0` remain unresolved.

The recovered functions contain two explicit reads through context `+0xd0`,
both in `FUN_bfc216a8`, and no explicit store through that expression. Ghidra
references to absolute `0x9fc21bd0` likewise show the two reads and no write.
The total function count reported by `DumpC` can vary with function discovery,
so it is not used as a completeness criterion. This does not rule out a write
through a computed alias or an unresolved service.

## Static call shapes through `0xbfc21a39`

A fresh Ghidra pointer-literal scan of the byte-identical member found exactly
nine slots containing `0xbfc21a39`; Ghidra reports 22 computed-call
references to the aligned stub address `0xbfc21a38` across these nine callers.
The recovered call shapes below show arguments as `(a0,a1,a2,a3)`; repeated
calls in loops and branches are grouped:

| Literal slot | Caller | Recovered call shapes |
| --- | --- | --- |
| `0x68021c7c` | `FUN_68021c00` | `(param+0x92,0,2,1)`; `(param+0x94,0,0x20,1)` |
| `0x68061d18` | `FUN_68061c00` | `(0x9fc21b08,0,0xec,1)`; `(ctx+0x40,0,0,0x17)`; `(ctx+0x44,0,0,0x12)` |
| `0x68061d8c` | `FUN_68061d2c` | `(*(param+0x40),0,0,0x1a)`; same first three arguments with `a3=0x19`; `(param+0x40,0,0,0x18)`; `(param+0x44,0,0,0x13)` |
| `0x680a201c` | `FUN_680a1ed4` | `(param+3,param3,4,0)` in dispatch case `0xb` |
| `0xbfc21464` | `FUN_bfc21430` | `(global+0x4c,0,0,0x16)` |
| `0xbfc21914` | `FUN_bfc218a8` | `(*(param+0x40),0,0,0x19)`; `(param3,param+0x1c,0xc,0)`; cleanup call `(*(param+0x40),0,0,0x1a)` |
| `0xbfc21a18` | `FUN_bfc21964` | `(param[0x11],0,0,0x14)` |
| `0xbfc2751c` | `FUN_bfc274c8` | `(dst,source,chunk_length,0)`; possible wrap remainder `(dst+first_length,base,remainder,0)` |
| `0xbfc277d8` | `FUN_bfc27734` | `(param[0x10],0,0,0x19)`; same with `a3=0x1a`; `(0,0,0,0xc)` |

The zero-initialization hypothesis is materially better supported, but remains
unconfirmed: three calls in two callers use `(destination,0,length,1)`, for
lengths `2`, `0x20`, and `0xec`; the `0xec` extent covers the context `+0xd0`
slot at offset `0xc8` from its supplied base. The explicit caller field stores
in `FUN_68021c00` are consistent with those short-range calls initializing
record fields. However, the same literal is also called with nonzero source
arguments and `a3=0`, and with `(a1,a2)=(0,0)` and `a3` values `0x12`,
`0x13`, `0x14`, and `0x16` through `0x1a`. That variation is evidence for an
overloaded resident service or operation-selector interface, not proof that
`a3=1` means zero-fill. With the resident body absent, the strongest bounded
statement is that the `0xec` call passes a range containing `+0xd0` to a
service whose other callers include zero-fill-shaped calls; the service's
effect on that slot is still unknown.

## Stock/community caller and loader search

I scanned every file recursively under both `analysis/stock` and
`analysis/community`, deduplicating identical file hashes within each corpus,
for little-endian references to the entry pointer `0xbfc218a9`, the worker
pointer `0xbfc216a9`, context addresses `0x9fc21b00`, `0x9fc21b08`, and status
slot `0x9fc21bd0`, plus the initializer `0x68061c00/0x68061c01`. The entry,
context, and initializer references outside `fw/mmm_bp.al` occur only in the
container copies `afi/FWIMAGE.FW` and `dec.bin`; their offsets correspond to
embedded `mmm_bp.al` content, not separate callers. The firmware package
directory labels the member `MMM_BP  AL`, but that packaging record does not
show who loads it or what arguments the runtime loader supplies.

The package-table record is byte-identical in stock and community containers:
it begins at `dec.bin+0x62c20` and `FWIMAGE.FW+0x420`. The 0x3000-byte module
payload is at `dec.bin+0x8e800` and `FWIMAGE.FW+0x2c000`; each payload matches
the extracted `mmm_bp.al` exactly (SHA-256
`ab5d98b578406c4e04fb7b546317fbec0d36ce4a7c139ef0922b06bb4b753c5b`). Thus
the container pointer hits are explained by the embedded member itself.

The only nearby decoder-related filenames found in the extracted AP images
are `mmm_mp.al` in `mengine.ap` (file offset `0x144c`) and `mmm_id.al` in
`music.ap` (file offset `0xeef0`). `manager.ap` is byte-identical in stock and
community (SHA-256 `d94d2406d5e88efee1ffacff2d399ce7d865410ac7458efdb9f185eae359af7a`)
and contains no `mmm_bp` name. These filename/manifest facts do not identify a
runtime loader or connect the named modules to the `MMM_BP  AL` record.

The corpus container hashes differ (stock `afi/FWIMAGE.FW`:
`edb04f1119f2caa4710dbfad67e3c79eee8fb0a7446c66224768f0cf9dbfd7dc`; community:
`c87035fa32bd83a2c2d36f5564ff4ab049d3f8cdc3ec9a2a75a8eb7a4cb7d745`), while
the `mmm_bp.al` hash is identical. This was a direct-literal scan, not a full
disassembly of every loader or a proof against computed addresses and runtime
registration.

## Dynamic entry/init p-code boundary

`tools/ghidra/run_mmm_bp_init_probe.sh <mmm_bp.al>` runs the extracted MIPS16
entry at `0xbfc218a8` with synthetic caller arguments. The real entry's
operation-0 dispatch executes `FUN_680a1ed4`, which calls the real initializer
`FUN_68061c00`; the dispatcher returns `0x9fc21b08` through the caller's
result pointer. The probe uses a RAM-backed context initialized with
`0xa5` sentinels, a bounded stack, a step limit, and no AP code or literal
rewrites. Both the stock and community files pass with the same pinned
`mmm_bp.al` SHA-256.

The run captures eight calls across two unresolved resident stubs. Stub
`0xbfc21a38` receives `(context,0,0xec,1)`, then `(context+0x40,0,0,0x17)` and
`(context+0x44,0,0,0x12)`. Stub `0xbfc21a3c` receives two shared-memory
requests with selectors `0x49`, then registration requests `0x33`,
`(stack_record,3,0xff,0x4a)`, and cleanup selector `0x34`. The probe provides
only synthetic nonzero handles for the two `0x49` calls so the AP path can
continue; all resident side effects are omitted. The resulting context
contains those handles at `+0x58` and `+0x70`, and explicit initializer fields
match the decompilation.

The `0x4a` stack record is now inspected during that same p-code run. It
contains callback pointer `0xbfc27735` (`FUN_bfc27734`), context pointer
`0x9fc21b08`, third field `0x9fc1bdf0`, and 16-bit field `0x4b0`;
the accompanying arguments are `(3,0xff,0x4a)`. `FUN_bfc27734` consumes the
context pointer and runs a loop with resident operations `0x19`/`0x1a` and a
wait-like operation `0x31`. This record shape is consistent with registering
or launching a worker, but does not identify the service contract. The service
remains stubbed, so the probe proves the callback descriptor passed to it,
not that a thread is created or the callback is invoked.

This gives an independent comparison point for MEngine's selector-`0x4a`
record, which also begins with a MIPS16 callback pointer. The resident entry
addresses differ (`0xbfc21a3c` here and `0xbfc11cc8` in MEngine), so the
similarity supports a callback-registration hypothesis without proving that
both services have identical semantics.

Ghidra's memory-write tracker is enabled only after synthetic setup and tracks
all writes during the AP run. Under the no-effect model for intercepted stub
`0xbfc21a38`, it reports no writes to any of the four bytes at context `+0xd0`,
and the final word remains `0xa5a5a5a5`. The tracker also records known AP
writes to the result pointer, context base, and synthetic handle field, which
checks that write tracking is active. This is direct evidence about the
executed AP path under that bounded stub model. It does not establish what the
real resident service does, prove a zero-fill contract, or identify the
device's runtime loader/caller. Those remain open.

## Targeted audio/runtime caller checks

I also mapped the likely extracted owners from the 2025-06-26 corpus in
Ghidra 12.2 and scanned their mapped bytes for the AP entry pointer and its
aligned address. The entry word in `mmm_bp.al` is `0xbfc218a9` (execution
address `0xbfc218a8`). The following candidate images produced no direct
literal occurrence of either value:

| Candidate image | SHA-256 | Ghidra mapping result | `0xbfc218a9` / `0xbfc218a8` occurrences |
| --- | --- | --- | --- |
| `fw/mengine.ap` | `1340bac3cb68043f7a9651ee7959c2f502e6759c7debe0626f07ef9231896d4d` | `LoadAP`: 35 blocks, 219 prologue functions, entry `0x60010d30` | 0 / 0 |
| `fw/music.ap` | `faa6b68a68de78ef45c2a9d9ad664b6b6310a93f21f9a07b29467827abd49193` | `LoadAP`: 126 blocks, 785 prologue functions, entry `0x40014c30` | 0 / 0 |
| `fw/kernel.drv` | `58f9d0c62bdbdab7cfeee1c5e65bbe8b88ee962f8eecd3dd0515d1a1056d8bd6` | `FlatLoad`: 18,944 resident bytes from file `0x200` at `0xbfc08000`; 130 prologue functions | 0 / 0 |

The Ghidra scripts were `LoadAP.java`, `FlatLoad.java`, and
`FindPointerRefs.java` from `tools/ghidra`; analysis was static and used
temporary projects. The extracted `mengine.ap` exposes the ASCII filename
`mmm_mp.al`, and `music.ap` exposes `mmm_id.al`; neither exposes
`mmm_bp.al`. The nearby `mmm_mp.al` function `FUN_68312d00` is already traced
as a format-index-to-`.dsp` filename selector; its recovered table does not
select `mmm_bp.al` and is not evidence for the missing decoder-module loader.

### Named module request in `mengine.ap`

A separate Ghidra reference/decompilation pass found a positive named-module
request in `mengine.ap` for `mmm_mp.al`, in both stock and community images.
The stock AP SHA-256 is
`1340bac3cb68043f7a9651ee7959c2f502e6759c7debe0626f07ef9231896d4d`; the
community AP SHA-256 is
`b569605e9f66ef94023cf82d665ae119f6bbb7ed0c9d8f5e6c5be867ce91be49`. Both
`LoadAP` mappings place the filename at `0x6005114c` (file offset `0x144c`).
After `DumpRefsTo.java` runs Ghidra `analyzeAll`, the string has references
from `0x6005103e` (`DATA`) and `0x6005106c` (`PARAM`), both inside
`FUN_60051034`. That function calls through slot `0x6005116c`; the mapped
slot value is `0xbfc11cc9`, a tagged MIPS16 pointer to `0xbfc11cc8`. Its
decompiled call arguments are `(&"mmm_mp.al",1,0,2)`. Its caller,
`FUN_60051278`, calls it at `0x6005130e`. The relevant function and caller
byte ranges are identical across the two APs, and their Ghidra decompilations
match.

This is direct evidence that the extracted engine passes the `mmm_mp.al`
filename and those three numeric arguments to resident entry `0xbfc11cc8`.
Calling that entry a module loader is a strong filename-based inference; the
resident body is unavailable, so the effect and meaning of arguments
`(1,0,2)` are not proven. It gives a concrete resident-service candidate to
trace, but does not show that the same entry loads `MMM_BP  AL` or that any
decoder module is selected. Reproduce the string xref with `LoadAP.java`
followed by `DumpRefsTo.java 0x6005114c`, and the caller chain with
`DumpRefsTo.java 0x60051034` plus `DumpAt.java 0x60051034,0x60051278`.

For `MMM_BP`, the package record remains the only positive external
observation in this scope: `MMM_BP  AL` occurs at `dec.bin` offset `0x62c20` and at
`afi/FWIMAGE.FW` offset `0x420`. That establishes a named package entry, not a
runtime caller, load address, or transfer of control to `0xbfc218a9`. These
negative scans rule out only direct pointer literals in the mapped candidate
images; a computed lookup, resident ROM service, or unextracted loader can
still provide the edge. No exact loader function/caller has been established.

## Reproduction and next probe

The existing static member probe passes against the mounted stock and
community members:

```sh
bash tools/ghidra/run_mmm_bp_fade_probe.sh /Users/berkankarakus/Projects/mechen-m30-firmware/analysis/stock/fw/mmm_bp.al
bash tools/ghidra/run_mmm_bp_fade_probe.sh /Users/berkankarakus/Projects/mechen-m30-firmware/analysis/community/fw/mmm_bp.al
```

The dispatch edge and literals can be rechecked with `LoadAP.java`,
`DumpAt.java`, `DumpWords.java`, `FindPointerRefs.java`, and `DumpRefsTo.java` in
`tools/ghidra`; the observed references are Ghidra static computed-call
references, not dynamic execution. No device, firmware, or SD data was
written.

The candidate-caller scan used `LoadAP.java` followed by
`FindPointerRefs.java 0xbfc218a9,0xbfc218a8` for the two APs, and
`FlatLoad.java <kernel.drv> 0xbfc07e00 0x200` followed by the same pointer scan
for `kernel.drv`. The `FlatLoad` base accounts for the `0x200` file header so
the resident payload begins at `0xbfc08000`.

Next, trace the audio/runtime loader that selects the `MMM_BP  AL` package
member and invokes its header entry, then identify the resident implementation
or ABI contract behind stub `0xbfc21a38`. That is the shortest route to
distinguish a loader-provided status pointer from a resident-service write.
Keep that investigation separate from the `0xbfc164dc` ABI work.
