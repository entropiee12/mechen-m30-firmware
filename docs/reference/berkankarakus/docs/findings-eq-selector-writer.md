# EQ Custom UI and gain writer

## Corrected menu identification

The earlier identification of `music.ap` row `0x3301` as Custom EQ was wrong.
Its `music.sty` label is `Omezení hlas.` (volume limit), and its callback
`FUN_40414c00` edits that numeric setting. The type-3 popup is part of the
volume-limit UI.

The EQ labels are in `setting.sty`: Classical (`0x1c`), Custom (`0x1d`), Funk
(`0x1e`), Hip hop (`0x1f`), Jazz (`0x20`), Rock (`0x22`), and Techno (`0x23`).
The EQ screen implementation found so far is in `mainmenu.ap`, not the
previously suspected `setting.ap` Custom row or `setting.mcg` node.

## Mainmenu EQ screens

Evidence input: community `mainmenu.ap`, SHA-256
`1ef9b7a466a0f3a6bec8ce8654422c372fd68105ff94739077bfe34b435a1105`.
The decompile and pool inspection identify:

- `FUN_40154cec` as the EQ selector screen. It cycles selector values `0..7`,
  displays Custom gains from persistent block `0x9fc155f4 + 0x2c` when the
  selector is 7, stores the selected type at block `+0x19`, and calls
  `FUN_40154c78(type)` when the type changes.
- `FUN_40154f44` as the seven-band Custom editor. It loads a 12-byte working
  copy and a cancel backup from `0x9fc15620` (block `+0x2c`). Events `0x2901`
  through `0x2906` navigate, edit, confirm, or cancel. On an edit it copies
  the working data back to the block and invokes `FUN_40154c78(7)`.
- `FUN_40154c78(7)` copies seven bytes from `0x9fc15620` to
  `0x9fc1a19c`, then may send module `0x0d`, command `0x303`, payload `07 07`.

## State-machine route and persistence

Raw disassembly of `FUN_40015050` and the little-endian words at file
`0x1790..0x179c` confirm the state callbacks: main list `0x40054d39`,
`FUN_40094d9d`, EQ selector `FUN_40154ced`, and Custom editor
`FUN_40154f45`. The main-list handler calls resident function
`FUN_bfc165e0` for key events. On selection event `0x2c03`, that function
reads the selected menu code from the table at `0x9fc155d0 + 6 + 2*index`;
when the code is 3, it returns `0x8505`. The outer dispatcher maps that to
state 3, the EQ selector. This closes the menu-to-EQ route. Other selection
codes handled there are 2, 5, and 6, which take message or conditional paths.

On an invalid `0x22`-byte menu block, `FUN_40014d74` loads config item `0xe2`
as the menu order. The hashed `config.bin` record is
`[0,6,0,4,5,2,3,1]`: six entries, default order `0,4,5,2,3,1`. Thus code 3
is at index 4 (the fifth menu item) on a default initialization. This proves
the default position, not the localized label shown for that item.

At exit, `FUN_40014fac` writes the menu block with
`(0x9fc155d0, 0x10000, 0x22, 0x13)`; the block magic is `0x55ab`. It then
writes the separate `0x38`-byte settings block. The stored menu order can
therefore differ from the default after it has been persisted. The event that
causes AP exit remains unknown. `mainmenu.mcg` has three root nodes and no
direct pointer to either EQ screen.

`0x9fc155d0` is also used by `config.ap` while testing the index-needed flag.
That address overlap alone does not show that the flag and menu block share
data; the overlays may reuse RAM at different times. Their helper IDs,
lengths, and lifetimes must match before treating them as one object.

Cross-references show `FUN_40014efc` calls `FUN_40014d74` before entering the
state loop. The loader reads the 0x38-byte block at `0x9fc155f4` with
operation `0x12`, checks magic `0x55aa`, and initializes defaults if invalid.
`FUN_40014fac` is called only on the state-machine path to state 0 and writes
`(0x9fc155f4, 0, 0x38, 0x13)`. Slider confirm/cancel does not itself persist
the block. This establishes when the AP invokes the save service; the
resident service's flash-commit timing and power-loss behavior remain
unknown.

The decrement branch at file offset `0x3fcc` is `ff f7 1e 52`: `EXTEND`
followed by extended `SLTI` with signed immediate `-2`. MIPS16e specifies a
signed comparison, so the clamp branch is taken only when the current gain is
below `-2`; otherwise the next block subtracts one. Raw code therefore
implements a floor at `-3`. The original Ghidra p-code sweep treated the
extended immediate as unsigned and produced `+1 → -3`. A diagnostic run that
forced the signed condition before `BTEQZ` yielded `-3,-3,-2,-1,0,1,2` from
starting gains `-3..+3`, while executing the remaining stock instructions.
This isolates the discrepancy to Ghidra's p-code handling of this extended
`SLTI` encoding. The event decoder remains stubbed, so physical key mapping
was not tested.

The same report says confirm (`0x2905`) sets selector 7 and returns to the type
screen without copying or sending another message. Cancel restores the
12-byte backup to `0x9fc15620` but sends no compensating `{7,7}` update. The
extended local p-code probe reproduced this: after an increment then cancel,
the saved byte returned to 0, the engine-facing byte remained 1, and only the
edit's one `{7,7}` update was observed. The engine may therefore retain the
last previewed gains after cancel; this stale engine state is an inference from
the missing update, not an audio measurement. The type screen sends
`{0,param}` for type 0 and `{7,param}` for types 1–7; its Custom-gain copy path
is taken only for `param == 7`.

Probe source and raw output are in
[`mainmenu-eq-slider-pcode-2026-11-24`](evidence/mainmenu-eq-slider-pcode-2026-11-24/README.md).
The probe uses synthetic key events, a memcpy stub, UI/event stubs, and a
forced-ready result for the producer. It verifies the AP control flow under
those conditions, not device event delivery, resident-service semantics, or
audible DSP behavior.

## Remaining gaps

- State-1 menu code 3 returns `0x8505` and enters the EQ type screen. The
  default config order places it fifth; its localized label is unknown. The
  earlier direct-xref scan missed the pointer-table dispatch, so zero direct
  code xrefs are not evidence that the screens are unreachable.
- `mainmenu.sty` labels reportedly decode to Off, Rock, Pop, Soft, Jazz,
  Classical, Techno, Custom, which conflicts with the MEngine preset names
  (Rock, Funk, Hip hop, Jazz, Classical, Techno). The string-table base or the
  displayed labels need confirmation.
- The `setting.ap` EQ list's relationship to the mainmenu EQ state machine is
  unknown. Which overlay is used on the tested device is also unknown.
- The `music.ap` and `mainmenu.ap` producer paths are separate overlays. Which
  one is reached by the tested device UI is unknown.
- The engine-to-DSP consumer and audible effect remain unproven.

No device test or firmware modification is implied. Do not use the unrelated
`music.ap` volume-limit callback as evidence for EQ Custom.
