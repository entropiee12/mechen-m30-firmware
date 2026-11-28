<!-- Statik analiz bulgusu (cihazda test edilmedi). Alt ajan çıktısı; ana oturum aşağıdaki noktaları baytlardan doğruladı: SDK'da LIST_ITEM_BUFFER_LEN=52, browser.ap slot 0x32, playlist.ap 0xa5ed ve browser.ap 0x46cc baytları. Diğer iddialar ajanın okumasına dayanır; VERIFIED/V = dump veya baytta okundu, HYPOTHESIS/H = çıkarım. Kaynak dosya: m30-work/findings/browser_names_sort.md. -->

# Browser list-name buffer and sort compare (M30, community V1.292.00)

Labels: VERIFIED = read in decompile/bytes. HYPOTHESIS = inferred.
Dumps: ghidra/browser_community.c (B), playlist_community.c (P), music_community.c (M).

## 1. List item name buffer

### Reproducible current-AP check

The current community browser image can be checked without a device or a
firmware patch:

```sh
bash tools/ghidra/run_browser_name_probe.sh analysis/community/fw/browser.ap
```

The probe decompiles the directory initializer, directory callback,
library-mode callback, and dispatch table from the AP itself. It asserts the
two limits that matter for the truncation report: directory records are
initialized with `0x32`-byte slots, while the community library callback caps
the copied title at `0x40` bytes and writes a two-byte terminator. This is a
static reproduction of the current AP, not proof that every screen uses the
same path.

The host listbox can optionally preview a candidate 46-byte text payload
(50-byte slot minus an assumed two-byte BOM and terminator), but that layout
is not established for the directory provider. The binary establishes the
50-byte destination slot and a 25-UTF-16-unit request; it does not establish
whether the ROM provider writes a BOM, how it terminates a result, or whether
it truncates long names. The preview therefore defaults to provider text and
labels 46-byte clipping as an assumption.

- VERIFIED: SDK has the constant. `PD196/case/inc/common_ui.h:32`: `#define LIST_ITEM_BUFFER_LEN 52`
  (GBK comment: "max string size of a list item, 52 bytes"). Unit = BYTES. Users: ap/common/common_ui/ui_directory.c:39
  (`g_list_buffer[LEN*(LIST_NUM_ONE_PAGE_MAX+1)]`), ui_directory_sub2.c:122-127 (init_directory_env), browser_scene_menu.c:397.
- VERIFIED: the firmware does NOT use 52. `FUN_48754574` (B:5158-5170; same in M:11730) is init_directory_env:
  `*(g_dir_browser+4) = 0x32; for i=1..7: slot[i].buf = i*0x32 + list_buffer; slot[i].len = 0x32`.
  So 8 slots (1 title + 7 items) x 50 bytes = 400 bytes. Stock and community bytes identical (file 0x19274 in browser.ap, bank 0x48754400 @ file 0x19100).
  g_dir_browser at 0x9fc15598 (len +4, buf +8), g_file_list at 0x9fc155a4 (stride 0xc). Literals read from file 0x193b8/0x193bc.
- VERIFIED: caller is gui_directory `FUN_40cd4e00` (B:1511-1560): calls init_directory_env as
  (dir_control=0x9fc15604, list_buffer=0x40cd4c00, path_info). Arg order matches SDK. Pointer to the function at file 0x12128.
- HYPOTHESIS: list buffer 0x40cd4c00 is a banked data page (512 bytes: 0x40cd4c00..0x40cd4e00, just below the code bank 0x40cd4e00).
  400 bytes of it are the buffer; listbox_data likely shares it, so growth beyond 512/8=64 per slot is impossible without moving it.

The adjacent-address part is now reproducible with
`tools/ghidra/run_browser_layout_probe.sh`. The browser literal table contains
`0x40cd4c00` at `0x40cd5024` and `0x40cd4d90` at `0x40cd5040`; the latter is
the `path_info` argument used by the same directory initializer call. The
addresses are 0x190 bytes apart, exactly `8 * 0x32`, which makes the current
50-byte layout boundary a checked binary fact. This does not prove that the
whole 0x200-byte page is free for a larger layout, but it rules out treating
the 64-byte experiment as an in-place safe change.

The same probe also checks the decompiled call context: `FUN_40cd4e00`
passes the list buffer literal to the directory initializer, then passes the
`path_info` literal to the directory/path callback and a subsequent UI
operation. The overlap warning is therefore about a live adjacent structure,
not merely unused address space inferred from a page boundary.

The pointer chain below the initializer is now checked by the same probe. Its
literal pool identifies the directory descriptor at `0x9fc15598`, the
per-entry table at `0x9fc155a4`, and the directory operation wrapper at
`0x780db201`. In the directory callback `FUN_71352820`, the resident helper
`0xbfc16a2d` receives each slot's destination and `slot_length >> 1`; with the
verified `0x32`-byte length this requests 25 UTF-16 code units. The adjacent
helper `0xbfc16a21` updates entry metadata. This establishes the caller's
capacity, not what the ROM helper returns when a name is longer: it may
truncate, return a short alias, or use another provider rule. The ROM helper's
implementation is outside the extracted firmware.

The p-code probe was rerun against the community AP. Its synthetic filesystem
stub returned `FILE    TXT`; the real in-AP formatter produced
`FILE.TXT\0TXT`, and the callback passed the 25-unit request. A new checked
matrix also feeds the real callback ASCII-only, 23-character BOM-prefixed and
BOM-less UTF-16 names.
The BOM-prefixed 23-character name remains intact; the BOM-less name enters
the 8.3 formatter, which rewrites its beginning as UTF-16 bytes for `Long.`
followed by bytes taken from the unpadded name. The probe asserts these
different outputs, as well as list-window positions and failed reads. This
shows a concrete encoding-sensitive corruption path before display; it does
not establish which encoding the absent ROM filesystem provider returns on
the device, nor does it prove the browser callback reaches the marquee
renderer. The three-row case uses separate `0x32`-byte name slots and checks
each row's output. Non-ASCII, malformed, and truncated names were not tested.
Do not treat BOM-less provider output as confirmed device behavior.

The next layer is now separated as well by
`tools/ghidra/run_browser_fs_dispatch_probe.sh`. `0x780db201` is the
MIPS16 entry of `FUN_780db200`, a generic filesystem-operation wrapper: it
selects an implementation from `0x780db5d4` using the resident state byte at
`0x9fc15735`. Its community table entries are `0x713125dd`, `0x714525ad`,
and `0x713925f5`; this is operation-provider dispatch, not marquee rendering.
The directory implementation `FUN_713125dc` then uses explicit operation
values: `1` enters a selected item, `2` moves to the parent, and `3` resets
the path to the root (the underlying path operations are `0x2f`, `0x5c`, and
`0x3a`). Therefore the `0/3` argument seen during browser initialization is
filesystem navigation state, not a hidden “scroll disabled” flag. The
folder-name fix must remain at the name-buffer/provider boundary or in the
directory screen's later display path; changing this dispatch would alter
navigation semantics and is not a safe UI patch.
- Directory mode (fsel type 0): VERIFIED `FUN_71352820` (B:11883) calls
  kernel fs op 2 with requested capacity `name_len >> 1` (= 25 UTF-16 units),
  then `FUN_71352ab0` (B:12004) formats a non-BOM 8.3 name as `NAME.EXT`.
  The 25-unit request does not prove how the ROM provider handles longer
  names; the actual maximum returned/displayed name remains UNKNOWN.
- Truncation, library mode (fsel type 1): VERIFIED community `FUN_714d2658` (B:13681; copy at ~13790) copies `name_len-2` = 48 bytes
  from the record title (record+0, 64-byte field, FF FE BOM + UTF-16LE) then writes 2 zero bytes. 48 bytes = BOM + 23 chars. Cap 0x40 if len-2 > 0x3f.
  Stock twin: `FUN_714126e0` (B:12679). Playlist/other type 2 uses `FUN_71392658`; `FUN_71512628` (B:13936) caps 0x30 (album field).
- VERIFIED dispatch: `FUN_780db22c` (B:14849) indexes table at 0x780db5c8 (browser.ap file 0x46c8) with fsel type byte at 0x9fc15734:
  [0]=0x71352821 FAT dir, [1]=0x714d2659 library, [2]=0x71392659. Community changed [1] from 0x714126e1 to 0x714d2659 (file 0x46cc, 3 bytes `e12641`->`59264d`).
- drv_ui scans up to `0x66` (102) bytes, while the directory callback requests
  25 UTF-16 units into a 50-byte slot. The request size does not establish the
  provider's returned encoding or truncation behavior, so the point where a
  long directory name is cut remains unknown. The earlier doc guess
  "52 = chars" is wrong: the observed slot length is 50 bytes.

## 2. Sort / compare

- VERIFIED: browser.ap has NO sort and no case-fold compare (no `-0x20` upper-casing, no 0x71812500 bank). Directory mode lists in on-disk FAT/exFAT order
  (FUN_71352820 just walks entries sequentially via FUN_780db49c/FUN_780db460). Folders and files are not name-sorted by browser.ap.
- VERIFIED: sorting happens only in playlist.ap when it builds MUSIC.LIB. Shared bank 0x71812500 (playlist.ap file 0xa400):
  - `FUN_71812874` (P:12194) in-place quicksort of u16 index array, insertion sort (`FUN_718127cc`, P:12150) below 9 elements; mode byte param_5 selects compare.
  - mode 2 `FUN_71812548` (P:12016): byte-wise key compare, len capped at 100, ASCII a-z folded to upper (0x61..0x7a minus 0x20), ends when both bytes are 0.
    No numeric handling: "Track 10" < "Track 2". Non-ASCII compared as raw bytes (unsigned `byte`).
  - mode 0 `FUN_71812650` (P:12068): rank compare of u16 table. mode 1 `FUN_718126c0` (P:12092): track-number byte, then mode 0 rank.
  - Tie refinement `FUN_40114d54` (P:804) re-reads longer keys from file.
- VERIFIED: key is not purely ID3 or filename. `FUN_40094f2c` (P:401): tag reader for types <8 (sets tag-ok +0xa3=1). Otherwise, or on tag failure,
  `FUN_40094d60` (P:347) uses the file name from kernel op 2 (len 0x20) via `FUN_40094c8c` (P:315, 8.3 "NAME.EXT") as title, "No Artist/Album/Genre", track parsed from name digits.
  So All-songs order = title tag (ID3) if readable, else filename (HYPOTHESIS: LFN vs 8.3 depends on kernel op 2). Album view: album key, then track, then title rank.
- VERIFIED track storage: `FUN_40094fec` (P:435) stores `(char)record[+0xf2]` into a 1-byte-per-song array (so only the low 8 bits sort).
- Community diff mapping (playlist.ap, via bank table):
  - 0xa5ed, 3 bytes `844082`->`a440a2` = addr 0x718126ed inside `FUN_718126c0`: MIPS16 LB(0x84/0x82 high bits 10000) -> LBU (10100). VERIFIED bytes; decode is standard MIPS16.
    Matches decompile: track bytes compared as unsigned `byte`. This is the "unsigned track order" fix (128+ were negative).
  - 0x310c, 22 bytes = addr 0x4009510c inside `FUN_40094fec`: the decimal track fallback (`a0` digit *10 + `a1` digit). This is the "12 read as 21" fix.
  - 0x8e2e, 0x8ff4, 0x9078, 0x92ac (4-8 bytes each) plus new code 0x9318-0x9381: bank 0x480d4400 (0x480d462e..0x480d4b81) inside the function
    Ghidra mis-decompiles as `FUN_480d45e8` (all blocks "unreachable"). HYPOTHESIS: tag/track parsing patch with call sites into new code; not decoded.
  - 0x8e2e..0x92ac not in the sort bank. No community change to FUN_71812548 (string compare is stock).
- browser.ap community diff: 0x46cc = dispatch entry (see above); 0xec58-0xf12f = new `FUN_714d2658` (bank 0x714d2500, +0x158..0x62f, 1240 bytes);
  0x18e6a/0x18ef0 `04`->`08` = single constants in bank 0x48714400 (purpose not identified). None touch sorting.
- Repo docs agree: docs/library-builder.md "Sort, compare, and refinement" (same function list).

### Reference-SDK cross-check (not M30 proof)

The public [`Suber/PD196_ATJ2127`](https://github.com/Suber/PD196_ATJ2127/tree/maint)
`maint` source was inspected as a contract cross-check, not as a replacement
for M30 evidence:

- `case/ap/ap_browser/browser_comfunc.c` initializes the generic browser with
  `FSEL_TYPE_COMMONDIR`, `FSEL_MODE_BROWSER`, `FSEL_BROW_ALL`, and
  `ALLFILE_BITMAP`.
- `case/ap/ap_playlist/plist_fsel.c` calls `vfs_get_name()` with capacities
  `30`, `16`, and `32` in different paths. Its `filtrate_dir()` branch handles
  both an 11-byte non-BOM 8.3 result and an 8-byte BOM-prefixed Unicode result.
- `case/ap/ap_picture/pic_option_menu.c` and the video equivalents pass
  `LIST_ITEM_BUFFER_LEN / 2`, while the header defines that constant as 52
  bytes. This is consistent with a caller-supplied UTF-16-unit capacity, but
  does not specify the M30 ROM implementation.

This narrows the ABI possibilities without changing the M30 conclusion:
`FUN_40cd4e00` reaches the browser/fsel layer through indirect pointers, and
the extracted M30 files do not contain the ROM provider behind filesystem
operation 2. Therefore LFN-versus-8.3 behavior on the M30 remains
**UNVERIFIED**; the SDK behavior must not be copied into a firmware patch.

## 3. What a filename-natural sort would need (no patch written)

## 4. Conditional screen updater: playlist record path, not folder proof

The directory callback is not the only place that can touch a visible name.
The browser refresh path `FUN_40cd5218` conditionally walks the same 12-byte
entry table and calls `0x48714a21` (`FUN_48714a20` in the aligned bank) when
its state/context pointer is present. The recovered function:

- writes the UTF-16 BOM directly to `entry + 8`, the same name destination
  populated by `FUN_71352820`;
- obtains a small intermediate value through the now-located provider
  `0x48594511` using the list state and row index. The provider is in the
  browser's `0x48594400` bank, not in fixed ROM. That bank is also identified
  by the library analysis as the user-playlist file-helper bank;
- reads a record at `list_state + 4 + row_index * 0x14` through the resident
  helper `0xbfc16a25`. This proves a separate 20-byte row-record table exists,
  although the record fields and whether one field is a long filename are
  still unresolved;
- hands that value to the resident converter `0xbfc16a29`, with a bounded
  `0x1c` argument, to produce the displayed entry text.

This is useful evidence but not yet a safe patch recipe for folder names. It
shows a conditional playlist/library name-production boundary and a separate
20-byte row-record source. We must not treat that record as the directory's
long-name buffer: the folder callback passes a 50-byte destination slot and
requests 25 UTF-16 units through `FUN_71352820`, but the ROM provider's output
and truncation behavior remain unknown. We also cannot safely enlarge
`entry + 8` without proving which screen owns the fsel/list-state object.
The finding is reproducible with:

```sh
bash tools/ghidra/run_browser_screen_name_probe.sh analysis/community/fw/browser.ap
```

The probe checks the entry-table pointer, the aligned conditional screen
updater, the provider/converter pointers, the 20-byte record stride, and the
`entry + 8`/`0x1c` bounded destination contract. The dynamic probes below
close the control-flow and call-argument part of this question; only the
semantic ownership of the fsel/list-state object remains open.

The same probe passes against the stock and V1.101.11 browser members. The
provider boundary is therefore not a community-only change; version selection
does not currently offer a safer way around the directory slot limit.

### Dynamic branch check

`tools/ghidra/run_browser_refresh_pcode_probe.sh` now executes the real
`FUN_40cd5218` body in Ghidra's p-code emulator. The AP remains unchanged;
only the control structure, list table, and indirect callbacks are relocated
to writable synthetic RAM. With the context pointer at `param_1+4+8` cleared,
the function emits zero row-provider callbacks and takes one fallback update
callback. With that pointer nonzero, it emits seven row-provider callbacks for
the seven visible entries, with row indices `0..6`, and no fallback update.
This dynamically verifies the branch and callback argument contract that the
static decompile exposed. By itself it uses synthetic context values, so it
does not identify the screen type that owns the state.

### Directory callback through refresh fallback

The two indirect callbacks in `FUN_40cd5218` are distinct: literal
`0x40cd52d8` points to `FUN_48714a20`, while `0x40cd52dc` points to the
`FUN_780db22c` fsel dispatcher. The ordinary seven-row branch above stubs the
first callback and does not reach the second. The new
`tools/ghidra/run_browser_directory_refresh_probe.sh` runs the real refresh
function, dispatcher, and `FUN_71352820` directory callback together. It uses
a synthetic `list_state + 2 = 0` to leave all seven requested rows for the
refresh fallback; the dispatcher receives mode `3`, count `7`, start index
`1`, and the synthetic row table. Fsel type `0` selects `FUN_71352820`.
Before the harness rewrites callback-backed words in emulated memory, it
checks that the original update-callback literal is `0x780db22d`, confirming
that dispatch edge exists in the firmware itself.

The directory callback then makes seven calls to a synthetic filesystem
provider: each destination is a distinct 50-byte name slot, with a request for
25 UTF-16 units and mode `2`. A BOM-prefixed 23-unit test name survives the
real in-AP formatter in all seven slots. The database and converter service
leaves are also stubbed. Length and mode are checked on each of the seven
filesystem calls. The probe also sweeps `list_state + 2` through `0, 1, 6, 7,
8`: the ordinary callback handles `min(count, 7)` rows with indices starting
at zero, and the fallback handles the remaining rows. The dispatcher table
pointer advances by 12 bytes for each ordinary row; once the count reaches
seven, the fallback is not called. This boundary is identical in stock
V1.101.10 and community V1.292.00. It still does not establish who supplies
the real list count, reveal the ROM provider's actual output, or connect the
names to the listbox paint/marquee path. Reproduce with:

```sh
for count in 0 1 6 7 8; do
  bash tools/ghidra/run_browser_directory_refresh_probe.sh analysis/stock/fw/browser.ap "$count"
  bash tools/ghidra/run_browser_directory_refresh_probe.sh analysis/community/fw/browser.ap "$count"
done
```

The next target is the producer of `list_state + 2`, then the framework call
that paints and scrolls the resulting names. The moving red rectangle remains
unexplained.

### List-count initialization boundary

The scene constructor `FUN_40cd4e00` writes zero to the halfword at
`0x9fc155f8 + 2` before calling the directory initializer `FUN_48754574`.
The refresh loop later reads that halfword as the upper bound for ordinary
row callbacks. The decompiled directory initializer prepares seven 12-byte
row descriptors and performs filesystem setup, but its visible body does not
write this count field. This locates the reset and the read, not the later
writer: a list framework or a service reached indirectly may set the count
after initialization. The matrix supplies this value synthetically and does
not establish the normal device value.

A second consumer is `FUN_40094e08`. Its decompile reads the same halfword and
subtracts it from the list-state halfword at `0x9fc15612`, then calls through
the literal targets `0x780db201`, `0x780db28d`, and `0x780db22d` (verified in
the adjacent literal table). `0x780db201` is the known filesystem wrapper and
`0x780db22d` the known fsel dispatcher; the role of `0x780db28d` remains
unidentified. This places the count in a separate list-state update path as
well as the refresh loop, but does not show a write to the count or prove that
this function draws or scrolls text. The new static probe checks both the
count-address literal and the call expressions, and reproduces these facts on
stock and community APs:

```sh
bash tools/ghidra/run_browser_list_count_reader_probe.sh analysis/stock/fw/browser.ap
bash tools/ghidra/run_browser_list_count_reader_probe.sh analysis/community/fw/browser.ap
```

Ghidra xrefs identify two more in-AP readers. The event dispatcher
`FUN_40d14f14` reads the count through literal `0x40d15254` in its `0x2704`
and `0x2706` cases. Its decompile updates event-state bytes; no direct store
through that literal appears in this function body. `FUN_4875493c` also reads
the count through literal `0x48754a3c` and passes `row - count` as the second
argument to an event-context callback. No direct store through that literal
appears in this function body either. These are additional consumers, not
evidence for the missing count producer or for text painting; indirect writes
and code outside these functions are not covered. The new hash-pinned probe
checks both the xref source/target pairs and these decompile expressions in
stock and community APs:

```sh
bash tools/ghidra/run_browser_list_count_xrefs_probe.sh analysis/stock/fw/browser.ap
bash tools/ghidra/run_browser_list_count_xrefs_probe.sh analysis/community/fw/browser.ap
```

The caller is now dynamically executed by
`tools/ghidra/run_browser_list_count_consumer_probe.sh`. With synthetic count
`2` and state position `5`, the real function computes row `3`, calls the real
`FUN_780db28c` state setter with `a0=3` and the real `FUN_780db22c` dispatcher,
then reaches the real directory callback `FUN_71352820` for one row. The probe
verifies the setter's resulting state word is `3`. The callback writes a
synthetic BOM-prefixed name through the real in-AP formatter to slot
`0x9fc154e0`. The count query, path/metadata/list services, filesystem,
database, converter, and fixed-ROM calls are stubbed. The code path and result
match in stock and community V1.101.10/V1.292.00 APs. This links a count-based
list update to name production; it still does not connect the produced slot to
the listbox paint/marquee routine or show the device's real count/provider
output. Reproduce with:

```sh
bash tools/ghidra/run_browser_list_count_consumer_probe.sh analysis/stock/fw/browser.ap
bash tools/ghidra/run_browser_list_count_consumer_probe.sh analysis/community/fw/browser.ap
```

That outer caller boundary is now dynamically measured as well by
`tools/ghidra/run_browser_scene_caller_probe.sh`. The resident
`FUN_bfc16600` path calls the extracted GUI entry `0x40cd4e01` with
`a0=0x6000`, the fixed fsel pointer `0x9fc15408`, and `a2=state+8`. Before
the call, its `t8=0` initialization stores `state+24` at `state+16`; because
`a2+8 == state+16`, the actual `path_info+8` value is nonzero. The dynamic
probe catches this call at a RAM stub and verifies that the context word is
exactly the initialized state pointer. The result is identical in community,
stock, and V1.101.11 browser members.

This proves that the refresh branch is reachable from this extracted browser
scene caller. It still does not identify the semantic type of the fixed fsel
object at `0x9fc15408`, so the 20-byte record source must not be called a
long-folder-name store.

### Integrated scene-to-refresh chain

The end-to-end caller-to-refresh control path is now also executed by
`tools/ghidra/run_browser_scene_refresh_chain_probe.sh`. It runs the real
resident `FUN_bfc16600`, extracted scene `FUN_40cd4e00`, and refresh
`FUN_40cd5218`/`FUN_40cd52e0` bodies in one p-code session. It now also calls
the real `FUN_487546d8` list-state callback twice instead of replacing it with
a no-op. The scene's control object, row table, list state, and callback
targets are redirected to synthetic RAM; initialization, readiness, and
state-reset service boundaries are stubbed. The callback reads a synthetic
title pointer at resident address `0x9fc155a0` and seven synthetic row-name
pointers from `0x9fc155ac + 12*i`. The probe samples the loaded pointer
registers at the callback's title and row store instructions and compares them
with those source fields. At return, it checks the title pointer and all seven
row pointers; each row uses a 12-byte descriptor whose slot-length field is
`0x32` (50 bytes).
With the observed scene arguments and a bounded seven-row window, the real
refresh loop emits seven row callbacks for indices `0..6`; the successful run
has 2,283 host-loop iterations, of which 2,248 call `emu.step()` successfully.
The event-predicate callback is forced to zero twice to reach the refresh path;
this does not establish that real services naturally reach refresh. The
experiment executes the real list-state and refresh instructions with
synthetic name data, but does not execute the directory-name provider or prove
that these rows represent filesystem folders. Treat the callback records and
list window as synthetic inputs, not captured device state. The pinned script
passes on stock V1.101.10 (`browser.ap` SHA-256
`b6e25204…3eb8f2f4`) and community (`10b0e5d8…e3fed34b3`). The local
V1.101.11 `browser.ap` has the community member's identical SHA-256, so it is
the same binary-level observation, not an independent variant result. Re-run
the two distinct artifacts with:

```sh
bash tools/ghidra/run_browser_scene_refresh_chain_probe.sh analysis/stock/fw/browser.ap
bash tools/ghidra/run_browser_scene_refresh_chain_probe.sh analysis/community/fw/browser.ap
```

The registered row callback `FUN_487546d8` also reads the count halfword at
`0x9fc155fa` and writes per-row status bits into each 12-byte descriptor. With
the harness's seven-row window (indices 1..7), a synthetic count of 0 and base
0 yields `0x8100` for all rows; count 2/base 0 yields `0, 0, 0x8100...`; count
2/base 5 yields `0, 0, 0x8000...`; count 7/base 0 yields all zeroes. The
extended probe checks those computed bits against the callback's count/base
conditions and prints them. This proves the count and base affect row metadata
passed out of the real callback. The meanings of `0x8000`/`0x0100`, and any
connection to text painting, scrolling, clipping, or the red rectangle, are
still unknown. Inputs remain synthetic. Reproduce the matrix with:

```sh
bash tools/ghidra/run_browser_scene_refresh_chain_probe.sh analysis/community/fw/browser.ap 0 0
bash tools/ghidra/run_browser_scene_refresh_chain_probe.sh analysis/community/fw/browser.ap 2 0
bash tools/ghidra/run_browser_scene_refresh_chain_probe.sh analysis/community/fw/browser.ap 2 5
bash tools/ghidra/run_browser_scene_refresh_chain_probe.sh analysis/community/fw/browser.ap 7 0
bash tools/ghidra/run_browser_scene_refresh_chain_probe.sh analysis/stock/fw/browser.ap 2 5
```

The same callback emits an encoding-sensitive byte at row descriptor `+4`.
The real callback produces marker `0x7d` for synthetic UTF-16LE `FF FE`,
`0x7c` for UTF-8 `EF BB BF`, and a controlled fallback value (`0x42`) when
there is no BOM. In the no-BOM case it reads the pointer stored at
`0x9fc1558c`, then reads byte `pointer + 0x0e`; the harness seeds both in RAM.
The marker names/semantics are not recovered, and these formats
are test inputs, not captured filesystem output. This is a concrete
encoding-dependent row-metadata branch that could affect folder presentation,
but it does not prove a scroll effect or a B3 call edge. Reproduce with:

```sh
bash tools/ghidra/run_browser_scene_refresh_chain_probe.sh analysis/community/fw/browser.ap 7 0 utf16
bash tools/ghidra/run_browser_scene_refresh_chain_probe.sh analysis/community/fw/browser.ap 7 0 utf8
bash tools/ghidra/run_browser_scene_refresh_chain_probe.sh analysis/community/fw/browser.ap 7 0 plain
bash tools/ghidra/run_browser_scene_refresh_chain_probe.sh analysis/stock/fw/browser.ap 7 0 plain
```

The row callback/provider boundary is now executed by
`tools/ghidra/run_browser_row_provider_probe.sh`. It runs the real
`FUN_48714a20` and `FUN_48594510` functions while stubbing only the resident
file helper and fixed-ROM converter. For row `2`, the observed calls are:

| Stage | Runtime observation |
|---|---|
| provider seek | offset `2 * 0x14 = 0x28` |
| provider record read | length `0x14`, mode `0x11` |
| converter source field | little-endian halfword at record `+0x02` (`0xa3a2` in the sentinel run) |
| converter destination | `entry+8+2`, after the callback writes `FF FE` |
| converter bound | `0x1c` |

The same chain passes in community, stock, and V1.101.11. This proves that
the conditional refresh path can overwrite an entry's visible name slot from
a 20-byte row record before the UI marquee sees it. The provider reads the
little-endian halfword at record offset `+0x02` and passes that value as the
converter's first argument; the remaining record fields are not interpreted by
this path. The provider bank is also
the browser's identified user-playlist file-helper bank, so this is stronger
evidence for a playlist-record path than for a filesystem long filename. The
fixed fsel/list-state object and record fields still need semantic
identification before this can be connected to the folder symptom.

The result is therefore a useful dynamic boundary, not a folder-scroll patch:
the directory fix still needs a proven full-name source and ownership of the
fsel/list-state record before changing the 50-byte slot.

The verified byte boundary is represented in the host emulator by
`tools/sim/browser_row_record.py`. It preserves all 20 bytes, exposes only the
little-endian `+0x02` halfword consumed by the converter, and computes the
`list_state + 4 + row * 0x14` offset. It deliberately does not call that field
a playlist index or directory identifier.

### Candidate explanation for variable folder scrolling

The directory name slot can contain 23 visible BMP characters after its BOM
and terminator. At that length, fixed 8-pixel cells always measure 184 px,
below the 205 px viewport. The extracted `new_m.fnt` record advances vary by
glyph: the slot text `A very long folder name` totals 207 px using raw record
advance bytes, while 23 `I` characters total 115 px. The host display
rasterizer gives its own values and currently uses those for painting. This
makes variable advances a plausible explanation for some truncated names
scrolling and others staying still, but the browser's indirect measurement
callbacks have not been tied to `new_m.fnt`; the hardware metric remains
unproven.

The M30 call boundary to the marquee remains incomplete. Static analysis found
`drv_ui.drv` `FUN_260e4ff4` (file `0x9674`) forwarding two text records to the
B16 renderer and, conditionally, the A17 forwarder. The renderer/forwarder
bodies are outside the extracted driver, and no direct call from browser
`FUN_40cd5218` to this path was found. The `0x7c` argument is carried into the
renderer descriptor, but its unit and meaning are unknown; it cannot yet be
used as a measured scroll width.

`folder_ui.py` now reports raw-record, host-display, and fixed-cell threshold
results side by side without changing its rendered behavior. Reproduce with:

```sh
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=tools/emu:. python3 -m unittest \
  tools/emu/test_folder_ui.py
```

### Directory callback runtime boundary

`tools/ghidra/run_browser_directory_name_probe.sh` now executes the real
`FUN_71352820` directory callback with only its filesystem/provider pointers
redirected to RAM stubs. A three-case matrix covers the callback's before,
inside, and after current-index branches:

```text
case    current start rows provider-db-calls
before        0     5    1                6
inside        5     4    3                7
after         8     5    1                6
```

All three cases pass the same `row-stride=0xc`, `slot=0x32`, `fs-length=25`,
`fs-mode=2`, and `converter-limit=0x4` checks.

The callback consumes one 12-byte row descriptor, reads the name into the
row's destination with a 25-UTF-16-unit filesystem request (`0x32 >> 1`),
then runs the in-AP `FUN_71352ab0` 8.3 formatter before calling the final
converter with a four-byte limit. With a synthetic `FILE    TXT` response,
the exact fixed-buffer bytes are `FILE.TXT\0TXT`: the visible C string is
converted, while bytes after its terminator remain in the 12-byte slot.
The host helper `format_directory_83_name()` preserves this byte-level
boundary and leaves BOM-prefixed names unchanged. This confirms the
directory-side name preparation path without claiming that the missing
resident filesystem provider returns long filenames.

The directory callback probe now also varies the synthetic filesystem helper's
return value. For both `0` and `-1`, the real callback makes the converter call
once per row; in the `-1` case the stub deliberately leaves the name buffer
untouched, yet the in-AP formatter/converter path still runs. Starting from a
UTF-16 `Stale` sentinel, that case ends with bytes
`53 00 74 00 61 00 6c 00 2e 65 00 00`. This does not establish the ROM provider's
failure convention, but it shows that a `-1` return alone does not bypass the
remaining callback path in this harness. A separate scratchpad report claimed
that `-1` skips conversion, but its `convert=0xa3a2` field is the converter's
captured `a0` value, confirming the call. That scratchpad probe runs the
different `FUN_48714a20` provider; this repo probe runs the row callback
`FUN_71352820`. Neither result justifies a model rule that failed reads skip
conversion.

Two separate behaviours exist, so first decide the target:
- A. Directory browse (FAT order): browser.ap has no sort. Needs a new sort over the directory entries at the listing layer
  (`FUN_71352820` + fsel enter-dir/count code at 0x71352500 bank) plus an index map from sorted position to FS entry
  (fsel select-by-index calls). Not feasible without big new code and a RAM index (up to thousands of entries); unlikely in tight bank space. HYPOTHESIS.
- B. MUSIC.LIB order (playlist.ap): change title key source to filename (in `FUN_40094f2c`/`FUN_40094d60`: always take the name path, skip tag title)
  and replace mode-2 compare `FUN_71812548` (0x71812548..0x71812650, 0x108 bytes) with a natural compare (digit-run compare, fold case).
  The key buffers are fixed: 100-byte compare cap, key length 16 bytes at 4,000 songs refined by `FUN_40114d54`, so natural compare needs full-key re-read to be right.
- Free space: HYPOTHESIS only. Bank 0x71812500 has no free space: zeros from file 0xa99c (addr 0x7181299c) to 0xac00 are runtime scratch
  (key bufs 0x71812a9c/0x71812b00, stack frames 0x71812b64/0x71812c2c, 0x71812cf4). Compare would have to go into another bank tail
  (zero runs >=128 B in playlist.ap: 0x9382 (126, bank 0x480d4400 after community code), 0x3350 (176), 0x3b44 (188), 0xbb7c (132), 0x12580 (128)) after
  checking each is not bank data, or a whole unused bank. Reaching it needs a JAL/EXTEND call site: change the mode-2 compare-pointer literal at 0x71812a88 (FUN_71812874 stores 0x71812a80/a84/a88 into 0x9fc15500 for modes 0/1/2) -
  the compare is called through that pointer, so only that literal needs to change.
- Not verified: kernel op 2 name format (LFN vs 8.3), kernel text converter, fsel mode used by the Browser folder view at runtime.
