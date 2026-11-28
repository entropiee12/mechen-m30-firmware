# Mainmenu EQ slider p-code probe

This read-only probe executes `mainmenu.ap` MIPS16 code in Ghidra p-code with
synthetic key events and service stubs. It does not model the physical UI,
flash storage, ROM services, or DSP hardware.

## Input and reproduction

- Input: `analysis/community/fw/mainmenu.ap`
- SHA-256: `1ef9b7a466a0f3a6bec8ce8654422c372fd68105ff94739077bfe34b435a1105`
- Probe: [`RunMainmenuEqSliderProbe.java`](../../../tools/ghidra/RunMainmenuEqSliderProbe.java)
- Runner: [`run_mainmenu_eq_slider_probe.sh`](../../../tools/ghidra/run_mainmenu_eq_slider_probe.sh)
- Original two-case output SHA-256: `81a0385f3e3f35c4493d5fc9c52cd9ab118709d879474a057f3eae72208a2274`
- Three-case output including cancel: [`cancel-run.log`](cancel-run.log), SHA-256
  `e2445606129e11dda457a1d8d39da118c8327c69e99bceeb0bf75252aa24d9f0`
- Ten-case decrement sweep: [`sweep-run.log`](sweep-run.log), SHA-256
  `fbc8926f6d4453c2baf82fa6662440e08b819b1e3fac77ffce7a7e29969057e8`
- Seventeen-case trace and signed-condition diagnostic sweep:
  [`isa-corrected-run.log`](isa-corrected-run.log), SHA-256
  `18505d9e551ba5bd3f77a6b8074bceed4929c0c1db5d6dd01c068fdee4838df2`
- Raw decrement instructions: [`decrement-extended-full.log`](decrement-extended-full.log),
  SHA-256 `31540ee27dcec52d505396061428fc1af96dc92b2ea0bb601986ba8a8a8c42c8`.
- State dispatcher and save path: [`route-persistence-disassembly.log`](route-persistence-disassembly.log).
- Resolved instruction references: [`route-refs-run.log`](route-refs-run.log),
  SHA-256 `be06a3fe3afbcef5ce116404dabe1a556f527d6ec64bbd5ad2c9b2a2cf3c9bb9`.
- Main-list callback decompile: [`menu-callback-run.log`](menu-callback-run.log).
- Code-3 selection and exit-save decompile:
  [`menu-code-3-rerun.log`](menu-code-3-rerun.log), SHA-256
  `559e80a80f8b58662ac257f4fca7861817e178648e84a3ebd4363ed41085c1c0`.
- Load/save references: [`persistence-refs-run.log`](persistence-refs-run.log),
  SHA-256 `13d3963413893d64523fbbf583aad294beeedd058409657e0e6b41839723a588`.

Run from the repository root:

```sh
bash tools/ghidra/run_mainmenu_eq_slider_probe.sh \
  analysis/community/fw/mainmenu.ap \
  docs/evidence/mainmenu-eq-slider-pcode-2026-11-24/run.log
```

## Observed cases

1. Starting gains `(0,-1,1,-2,2,-3,3)`, a synthetic `0x2903` event changed
   gain 0 from 0 to 1. The real AP instructions copied the 12-byte working
   data to `0x9fc15620`, copied seven bytes to `0x9fc1a19c`, and reached the
   producer call with module `0x0d`, command `0x303`, payload `07 07`.
2. The original decrement sweep injected event `0x2904` at starting values
   `-3..+3`. It stored `-3` for every starting value other than `-3` itself.
   Raw bytes at file offset `0x3fcc` are `ff f7 1e 52`: MIPS16e `EXTEND`
   followed by `SLTI` with the extended immediate `-2`. MIPS16e defines this
   extended comparison as signed. For `+1`, `+1 < -2` is false, so `BTEQZ`
   branches to the one-step decrement (`ADDIU -1`) and stores `0`. The Ghidra
   p-code run instead made the condition true, took the `-3` clamp, and
   produced the reported `+1 → -3`.
3. The diagnostic sweep forced only that branch condition to the signed
   predicate `gain < -2` immediately before `BTEQZ`; all following AP
   instructions remained unchanged. Starts `-3..+3` then produced
   `-3,-3,-2,-1,0,1,2`. This is a harness correction to isolate the emulator
   mismatch, not a firmware modification or complete emulation of the
   extended `SLTI` instruction.
4. A synthetic confirm event `0x2905` stored selector 7.

The producer readiness check was forced true. Event retrieval, UI service
calls, and the resident copy operation were stubbed; copy behavior was modeled
as memcpy. The captured message therefore establishes the AP path under these
conditions, not that the device reaches it or that the resident service has
the modeled semantics. The probe did not exercise persistent save/restore.

The original raw Ghidra output is [`run.log`](run.log); the traced condition
is in [`trace-run.log`](trace-run.log). The signed interpretation follows the
MIPS16e specification for `EXTEND` and extended `SLTI` ([MIPS Architecture for
Programmers, Volume IV-a](https://0x04.net/~mwk/doc/mips/MD00077-2B-MIPS1664-AFP-02.50.pdf)).

The three-case run executes increment-confirm, decrement-confirm, and
increment-cancel. In the cancel case the editor's stored gain returned to 0,
while the engine-facing gain remained 1; exactly one `{7,7}` producer update
was observed (the edit update), with no cancel compensation. The later
ten-case run repeats these and sweeps decrement inputs `-3..+3`. Both runs use
synthetic event, memcpy, UI, and readiness stubs; the event decoder ABI remains
inferred. See [`cancel-run.log`](cancel-run.log) and
[`sweep-run.log`](sweep-run.log).

## Screen route and persistence timing

Raw disassembly confirms the pointer words at file `0x1790..0x179c` are
`40054d39, 40094d9d, 40154ced, 40154f45`. PC-relative indirect calls in
`FUN_40015050` select these callbacks for states 1–4. State 3 is the EQ
selector `FUN_40154cec`; state 4 is the Custom editor `FUN_40154f44`. The
dispatcher routes state 1 return `0x8503` to state 2 and `0x8505` to state 3;
state 3 returns `0x8006` to state 1 or `0x8506` to state 4; state 4 returns
`0x8505` to state 3.

On selection event `0x2c03`, resident `FUN_bfc165e0` reads the selected code
from `0x9fc155d0 + 6 + 2*index`. Code 3 returns `0x8505`; the top-level
dispatcher turns this into state 3, the EQ selector. The selected index,
count, and table base use `0x9fc155cc`, `0x9fc1562c`, and `0x9fc155d0`.
On invalid-block initialization, config item `0xe2` is
`[0,6,0,4,5,2,3,1]`, placing code 3 fifth. Its displayed label remains
unknown. The handler's `0x8622` case is a separate display action.
`mainmenu.mcg` has three root nodes and no direct pointer to the EQ screens.

`FUN_40014efc` calls `FUN_40014d74` before the state loop. The loader reads
the 0x38-byte block at `0x9fc155f4` with operation `0x12`, checks magic
`0x55aa`, and initializes defaults if invalid. Only the transition to state
0 in `FUN_40015050` calls `FUN_40014fac`; it writes
`(0x9fc155f4, 0, 0x38, 0x13)`. It also saves menu order with
`(0x9fc155d0, 0x10000, 0x22, 0x13)`; the menu block magic is `0x55ab`.
Thus mainmenu code persists these blocks on AP exit, not at slider
confirm/cancel. When the resident service commits the writes to flash, and
behavior on power loss before AP exit, remain unknown. `config.ap` uses the
same numeric RAM base for an indexing flag, but that address match alone does
not establish shared data; compare overlay lifetimes and helper IDs/lengths.

On cancel, the editor reportedly restores its 12-byte working backup to
`0x9fc15620` but sends no compensating `{7,7}` update. A stale engine preview
is therefore possible, though no audio measurement was made. The selector
sends `{0,param}` for type 0 and `{7,param}` for types 1–7; its Custom-gain
copy path is taken only when `param == 7`.

An unresolved label mismatch remains: `mainmenu.sty` was read as
Off/Rock/Pop/Soft/Jazz/Classical/Techno/Custom, while the MEngine preset rows
are Rock/Funk/Hip hop/Jazz/Classical/Techno. The string-table base and the
labels actually displayed by the device still need confirmation.
