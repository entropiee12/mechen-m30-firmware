# MUSIC.LIB node format evidence

This note records the byte-level evidence behind the host decoder for the
16-byte section nodes. It does not claim that the public comparison file is an
M30 dump.

> **Current status (2026-11-18):** the later stock-MIPS/QEMU experiment below
> supersedes the Python-model claims about 56 ARTIST mismatches, a `9th Wonder`
> encoding-group rule, and 8–10-character node thresholds. Those were artifacts
> of the host candidate model, not observed device behavior. With device
> ALLSONG ranks supplied and the text converter replaced by a test stub, stock
> MIPS results depend on the test stub's post-NUL tail policy. The stale-tail
> seeded run reproduced every order but separated the `Indelible` /
> `Indelible’` nodes. A separate `NUL_ZERO` seeded run also separated that pair
> and introduced a `9th Wonder` order mismatch; aggregate node totals partly
> cancel across those groups. A third, narrower terminal-unit hypothesis
> reproduced every seeded order, node, and index table. The middle-unit branch
> fits 52 album records; the terminal-unit branch fits only the two ARTIST
> records that motivated it. The exact corpus match is real, but the narrow
> branch may be overfit and does not prove the resident converter.

## Evidence

The repository's reference builder emits every node with:

```text
struct.pack("<BBHHHHHHH", node_type, depth, child_count, song_count,
            first_record_index, first_child_index, parent_index,
            previous_index, next_index)
```

That gives this fixed layout:

| Offset | Width | Host field |
|---:|---:|---|
| `0x00` | `u8` | `node_type` |
| `0x01` | `u8` | `depth` |
| `0x02` | `u16` | `child_count` |
| `0x04` | `u16` | `song_count` |
| `0x06` | `u16` | `first_record_index` |
| `0x08` | `u16` | `first_child_index` |
| `0x0a` | `u16` | `parent_index` |
| `0x0c` | `u16` | `previous_index` |
| `0x0e` | `u16` | `next_index` |

An independent public comparison repository contains a raw `MUSIC.LIB` file
and describes its `.LIB` files as coming directly from an MP3 player. Its
header reports 251 records. The section directory references the same
ordering used by the local builder: `ALLSONG` order/inverse tables, then
`ALBUM`, `ARTIST`, and `GENRE` node/table groups. The first observed nodes
also decode cleanly with the layout above: `node_type=1`, the expected depth,
`song_count=251`, `first_record_index=23`, and `0xffff` link sentinels where
the group has no corresponding link.

The comparison is not a byte-identical reproduction of the local builder.
Running `libmodel.py` and `libdiff.py` against the public file keeps the
record count and section locations compatible, but reports different sort and
group results: the public `ARTIST` directory has counts `(1, 1, 0)` while the
local model derives `(2, 2, 0)`, and the public `GENRE` directory has
`(1, 1, 1)` while the model derives `(1, 2, 2)`. This is evidence that the
wire layout is shared while grouping/tie-breaking behavior may vary by
firmware or by the source library's contents.

The local builder and `libdiff.py` also establish the three per-record table
roles after the node slots: reference `3` is the section's sorted record
order, reference `4` is its inverse rank, and reference `5` is the per-song
node/group index when that section has levels. `tools/sim/library_file.py`
exposes these as `LibrarySectionTables` without validating or rewriting the
values. The public comparison's grouping divergence is why the host model
does not turn these tables into a user-facing sort policy.

## New Ghidra confirmation

Ghidra 12.2 was run against the extracted 2025-06-26 `playlist.ap` with:

```sh
bash tools/ghidra/run_library_index_probe.sh \
  /path/to/2025-06-26/fw/playlist.ap \
  /private/tmp/m30-ghidra-library-index-current
```

The probe decompiles the four index passes, the node writer, the shared sort
dispatcher, and the text-key converter. The node writer confirms that the
first record is read from the sorted order array, each node consumes one
positive run length, child counts are accumulated from a child run stream,
and parent indices advance from a parent run stream. It writes the previous
and next links from the node counter and terminates the final next link with
`0xffff`. The main entry calls the passes in `ALLSONG`, `ALBUM`, `ARTIST`,
`GENRE` order. The converter distinguishes UTF-16LE BOM, UTF-8 BOM, and
plain-byte paths, with the BOM paths reading twice the requested field width
before conversion.

The preserved decompile output `index.c` has SHA-256
`b4d24e168ca61c697c489404ad35d92644d6a0e0effb5b03b010e76dc356fcbc`.
The same hash was obtained for all twelve dated `playlist.ap` members in the
corpus (2022-08-05 through 2025-06-26). The decompile outputs are intentionally
kept outside the repository because they are derived from vendor firmware; the
probe is the canonical regeneration path. This twelve-release match makes
the index/node/key-converter contract a cross-version finding, but it does not
resolve the opaque location bytes or prove that the public sample's grouping
is M30-specific.

An additional nine-date validation used newly extracted firmware files whose
current `playlist.ap` hashes are not all identical. The standalone
`run_library_index_probe.sh` passed for every available date from 2022-09-14
through 2025-06-26, including the 2024-04-24 and 2025-06-26 variants. This
supports the narrower hypothesis that the index-pass/node-writer ABI is stable
even when the surrounding `playlist.ap` member changes. It does not prove that
the sort comparator or duplicate-key tie-break produces the same order without
a matching M30-created `MUSIC.LIB` image.

An additional read-only probe dumps the tie-refinement and compare callbacks
to `<persistent-evidence-root>/decompile/library-tie-2025-06-26/tie.c`:

```sh
bash tools/ghidra/run_library_tie_probe.sh \
  /path/to/2025-06-26/fw/playlist.ap \
  /path/to/m30-evidence/decompile/library-tie-2025-06-26
```

The preserved `tie.c` has SHA-256
`80c8cd7af541f3327e459af47f487a0bebdd98334c1de1c503649acefb98b559`.
`FUN_40114d54` tracks the compared-byte length for each sorted entry, finds
equal runs, reloads a bounded additional key slice of `0x10000 / run_length`
bytes (capped by the remaining field width and 100 bytes), re-sorts that run,
and repeats while a run can still be refined. `FUN_71812548` uppercases ASCII
bytes, compares up to the supplied length, and returns equality when both
current bytes are NUL. `FUN_71812874` is not a stable sort: it uses a
middle-element pivot, an explicit range stack, Hoare-style partitioning, and a
selection-style insertion sort for ranges of eight entries or fewer. The host
model keeps this stock path opt-in in
`tools/sim/library_index_sort.py::refine_sort_record_indices()`; the existing
default sorter remains unchanged and no index writer consumes the refined
result.

The reproducible cross-comparison tool is
`tools/emu/library_tie_refinement_probe.py`. It applies the recovered bounded
refinement shape to the device image and compares each candidate order with
the stored section table while feeding the device's preceding-section rank to
later passes. Against the captured M30 image it reports:

```text
ALLSONG 3963/4000 (first mismatch 65)
ALBUM 3981/4000 (first mismatch 859)
ARTIST 3944/4000 (first mismatch 83)
GENRE 4000/4000
```

Run it with:

```sh
PYTHONPATH=tools/sim:tools/emu \
  python3 tools/emu/library_tie_refinement_probe.py \
  <persistent-evidence-root>/device-sd/MUSIC.LIB \
  e386db27a9f47641e8755e5a58a903e6e66f83afe2be84c97d5d7e59de0f3c88
```

The stock-sort model was rerun on 2026-11-17 against the mounted
`/Volumes/MECHEN M30/MUSIC.LIB` file. Its hash matched the captured M30 image.
The remaining `ALLSONG` differences are 37 entries in small equal-visible-title
blocks such as FLAC/WAV pairs and repeated `Instrumental`/`Vocal` titles. The
19 `ALBUM` differences remain one contiguous boundary: three
`Dünya'dan Kovuldu` records precede sixteen `Duality` records in the device
file, while the candidate conversion puts the latter group first. The stock
MIPS code has now been executed under QEMU on the same M30 corpus; with the
device's preceding ALLSONG rank supplied, it reproduces the same 19-record
boundary. The remaining discriminator is how output bytes after the first
non-byte UTF-16 character are initialized; the corpus comparison below tests
that hypothesis but does not observe the resident service directly.

An explicitly experimental `stop-zero` conversion mode is available for this
edge case:

```sh
PYTHONPATH=tools/sim:tools/emu \
  python3 tools/emu/library_tie_refinement_probe.py \
  <persistent-evidence-root>/device-sd/MUSIC.LIB \
  e386db27a9f47641e8755e5a58a903e6e66f83afe2be84c97d5d7e59de0f3c88 \
  stop-zero
```

On the 4,000-record SD image it produces `ALLSONG` 3,994/4,000 and `ALBUM`
4,000/4,000, while `ARTIST` and `GENRE` stay at 3,944/4,000 and 4,000/4,000.
The 251-record public sample is unchanged by the mode. Examining the 19 album
positions shows why: they are the three UTF-16LE `Dü...` records and sixteen
ASCII `Duality` records. The three UTF-16LE fields contain U+0308 immediately
after `Du`. In the host `stale-tail` conversion, the unconverted low byte from
the UTF-16 `u` remains at key byte 2, producing a `Duu...` prefix that sorts
after `Duality`; the zero-filled candidate produces `Du\0`, which matches the
device's order. Thus the captured ALBUM table strongly favors a cleared output
tail for this specific conversion case.

This does not establish one conversion rule for every section. On the same
image, `stop-zero` leaves 6 ALLSONG positions and 56 ARTIST positions
unmatched, and ARTIST's captured encoding-correlated blocks remain unexplained.
The QEMU pass does not test either resident output behavior: its conversion
entry is redirected to `stub_text()`, and the title-rank-seeded run still
reproduces the 19-record ALBUM boundary with the stale-tail stub. Keep
`stop-zero` experimental and `stale-tail` as the default until one model
explains the independent section orders.

The probe now accepts `matrix` to vary `stale-tail` and `stop-zero`
independently for each section while seeding each later section with the
device's preceding rank:

```sh
PYTHONPATH=tools/sim:tools/emu \
  python3 tools/emu/library_tie_refinement_probe.py \
  <persistent-evidence-root>/device-sd/MUSIC.LIB \
  e386db27a9f47641e8755e5a58a903e6e66f83afe2be84c97d5d7e59de0f3c88 \
  matrix
```

All 16 combinations leave the `ARTIST` score at 3,944/4,000 and `GENRE` at
4,000/4,000. The best order-only combination is `stop-zero` for ALLSONG and
ALBUM, with either mode for ARTIST and GENRE: 3,994, 4,000, 3,944, and 4,000
matches respectively. This isolates each section using the device's actual
preceding rank; it is not a full simulated build.

`chain-matrix` runs the same 16 combinations but feeds each candidate section
rank into the next section, as a library rebuild does:

```sh
PYTHONPATH=tools/sim:tools/emu \
  python3 tools/emu/library_tie_refinement_probe.py \
  <persistent-evidence-root>/device-sd/MUSIC.LIB \
  e386db27a9f47641e8755e5a58a903e6e66f83afe2be84c97d5d7e59de0f3c88 \
  chain-matrix
```

The best chained candidate is `stop-zero` for ALLSONG and ALBUM, with either
mode for ARTIST and GENRE: 3,994, 3,998, 3,942, and 3,998 matches. Uniform
`stale-tail` gives 3,963, 3,973, 3,936, and 3,992. The QEMU stock MIPS pass
with the title-rank-seeded input produced 4,000, 3,981, 4,000, and 4,000.
Thus the ARTIST difference is not explained by choosing device ranks versus
candidate ranks alone; the Python candidate still differs from the stock MIPS
result. The QEMU harness redirects text conversion to `stub_text()`, so the
remaining comparison must separate key bytes from sort/refinement behavior
before either model is promoted.

The probe also accepts `rank-source-matrix`. It runs all eight per-transition
choices (device or candidate rank for ALBUM, ARTIST, and GENRE) under each
uniform conversion mode. The file is read once for the complete 16-case sweep:

```sh
PYTHONPATH=tools/sim:tools/emu \
  python3 tools/emu/library_tie_refinement_probe.py \
  <persistent-evidence-root>/device-sd/MUSIC.LIB \
  e386db27a9f47641e8755e5a58a903e6e66f83afe2be84c97d5d7e59de0f3c88 \
  rank-source-matrix
```

For `stale-tail`, using candidate ALLSONG rank for ALBUM lowers ALBUM from
3,981 to 3,973 matches. When ARTIST also consumes that candidate ALBUM rank,
ARTIST falls from 3,944 to 3,936. With `stop-zero`, device ranks give
3,994/4,000/3,944/4,000; candidate ALLSONG rank lowers ALBUM to 3,998, and
the all-candidate path gives 3,942 ARTIST and 3,998 GENRE matches. None of the
eight rank-source choices raises ARTIST above 3,944 for either uniform
conversion mode. The remaining ARTIST error is not explained by choosing a
different predecessor rank alone.

Earlier Python-only comparisons reported residual ALLSONG, ALBUM, and ARTIST
positions and suggested that `9th Wonder` needed an encoding-sensitive grouping
rule. The stock-MIPS experiment below shows that those residuals do not describe
the device's sort/group implementation: they came from the host candidate
model. Keep the Python converter sweeps as hypothesis tools only.

Against the hash-verified 4,000-record M30 image, replacement bytes `0x01` and
`0x20` both reproduce ALLSONG, ALBUM, and GENRE exactly; ARTIST remains
3944/4000. The same section scores hold whether later passes use device ranks
or candidate ranks. A sweep of all replacement bytes `0x01..0x25` found exact
section orders for `0x01..0x23`; `0x24` and `0x25` each produce 32 ALLSONG
differences, first at rank 896. That first group consists of duplicate visible
`03. LP Version.mp3` titles and is sensitive to equal-key quicksort order, so
the sweep constrains values only under the current sorter/refinement model; it
does not establish the actual replacement byte. A simpler adjacent-order test
alone accepts the broader `0x01..0x25` range.

The full replacement-byte sweep is reproducible with the opt-in
`replace-matrix` mode. For the captured device image, run
`python3 tools/emu/library_tie_refinement_probe.py <MUSIC.LIB> e386db27a9f47641e8755e5a58a903e6e66f83afe2be84c97d5d7e59de0f3c88 replace-matrix`.
It verifies the input hash and evaluates all 37 values from `0x01` through
`0x25`; the default conversion mode and emulator output are unchanged.

The stale-tail model's first mismatch is at ALLSONG rank 65: device records
3038 then 3274, candidate records 3274 then 3038. Their UTF-16LE title fields
contain U+0131 (`31 01`) before `.flac`/`.wav`. The old model stops at that
code unit and treats the suffixes as equal; the replace-and-continue model
orders the suffixes and matches the device. A prior stable-sort experiment
restored this pair only under the stale-tail comparator, while reducing that
model's total ALLSONG score from 3963/4000 to 3803/4000. It is therefore not a
valid general fix and does not explain the newer converter result. No sorting
behavior was changed in the emulator.

Earlier Python grouping experiments reported node-count mismatches for
`9th Wonder` and proposed text-length thresholds. Those results are superseded:
the stock MIPS pass produced the `9th Wonder` groups and node-index tables
correctly in the later controlled run. The unresolved node behavior is limited
to records 3924 (`Indelible`) and 3925 (`Indelible’`): the device merges them,
while the replace-and-continue test converter separates their keys. The
equality rule for this pair is unknown.

A cheap host-model split-semantics check used `replace-continue:01` for sort
and refinement, then `stop-zero` for node-run equality, with candidate ranks
chained between sections. It still produced 4,000/4,000 ALLSONG and ALBUM
orders, 3,944/4,000 ARTIST, and 4,000/4,000 GENRE. Node counts were 548
ALBUM, 362/706 ARTIST, and 30/438/708 GENRE, versus 549, 363/707, and
30/439/709 in the device file. Records 3924/3925 share an outer ARTIST group
under this model but remain in separate deeper ARTIST and GENRE groups. The
hybrid merges the Indelible pair at one level but overmerges elsewhere, so
this simple split does not reproduce the device nodes. This is a host-model
experiment only; it does not establish that firmware uses separate conversion
semantics for sorting and grouping.

### Stock MIPS passes executed under QEMU

The stock passes were run through `tools/library/test.sh` in a temporary
Debian 13 arm64 container with `gcc-mipsel-linux-gnu` 14.2.0 and
`qemu-user-static` 10.0.13. The input firmware member was the extracted
2025-06-26 `playlist.ap` (SHA-256
`7b1971f1496318a9682b0abc7681c058ded702aa533c97a32e428f5088b77b64`); the
M30 library hash was
`e386db27a9f47641e8755e5a58a903e6e66f83afe2be84c97d5d7e59de0f3c88`.
The repo and evidence mounts were read-only; QEMU outputs stayed inside the
temporary container. The reusable local tool image is
`m30-qemu-tools:debian-stable`.

This executes the stock sort, tie-refinement, grouping, and index-writing
banks, but the run is not fully stock: `testbanks.py` redirects resident text
conversion at `0xbfc16bb5` to `harness.c::stub_text`. Its function `0x1a`
behavior is inferred, not recovered from the kernel. The harness also calls
the stock key wrapper for scan-created title keys, so those use the same
inferred converter. These runs validate stock pass logic for the supplied key
bytes; Unicode-sensitive mismatches remain evidence about the converter and
its input state, not a direct test of the resident routine. See the call-chain
notes in `docs/library-builder.md`.

#### Controlled stock-MIPS run reported 2026-11-17

A newer scratchpad run executed the stock MIPS16 sort, rank-refinement,
grouping, and node-writing passes using the captured device ALLSONG order as
the preceding-rank input. Its text-conversion stub replaced each UTF-16 code
unit above U+00FF with `0x01`, continued through the string, and retained the
post-NUL tail. This converter behavior is a test hypothesis, not recovered
firmware behavior.

The report records 4,000/4,000 order entries for all four sections, 549/549
ALBUM nodes, and matching node-index tables. ARTIST has one extra outer node
(364 vs 363); GENRE has one extra level-1 node (440 vs 439). Both differences
come from records 3924 `Indelible` and 3925 `Indelible’`, which the device
groups together but this converter stub separates. The earlier Python-model
`9th Wonder` grouping and text-length threshold claims are therefore not
device rules.

This experiment validates the stock pass code for seeded ranks and the
specified stub output. It is not a seedless rebuild and does not identify the
resident converter. A separate seeded run with `NUL_ZERO` is recorded below.
The run inputs were the hash-checked M30 `MUSIC.LIB`
(`e386db27a9f47641e8755e5a58a903e6e66f83afe2be84c97d5d7e59de0f3c88`) and
2025-06-26 `playlist.ap`
(`7b1971f1496318a9682b0abc7681c058ded702aa533c97a32e428f5088b77b64`). Its
temporary harness/output remain in the agent scratchpad; bring that exact
reproducer into the repository before treating the run as locally reproducible.

#### Seeded `NUL_ZERO` comparison reported 2026-11-18

A separate one-condition Docker run used the same captured device ALLSONG
ranks, but compiled the text-conversion stub with `MODE_REPLACE`, `FILL=1`,
and `NUL_ZERO`. This is not the stale-tail condition above. The report gives
these inputs: device `MUSIC.LIB` SHA-256
`e386db27a9f47641e8755e5a58a903e6e66f83afe2be84c97d5d7e59de0f3c88`, stock
`playlist.ap` SHA-256
`7b1971f1496318a9682b0abc7681c058ded702aa533c97a32e428f5088b77b64`, and
seed fixture SHA-256
`86752ed323f2e3545a1a83a7994ab7df7f11b55755b7192f32176a9d877b7833`.
The reported output `out_stock.lib` SHA-256 is
`e07f2a97fc7b5688148b1079ae41c466da7d1e9a066add3e728515ef078c327c`.
The exact `FILL=1/NUL_ZERO` full run is recorded as Claude's reported result;
it was not repeated in this turn. Its hash-verified seed was available and was
reused for the independent probes and full candidate runs below.

The report gives order scores `ALLSONG=4000/4000`, `ALBUM=4000/4000`,
`ARTIST=3944/4000`, and `GENRE=4000/4000`. Node counts were 548 ALBUM,
363/706 ARTIST, and 30/439/708 GENRE, versus device counts 549, 363/707, and
30/439/709. Records 3924/3925 retained their device order positions in both
ARTIST (3184/3185) and GENRE (2296/2297), but the reported node boundaries
split the pair at ARTIST L0 and GENRE L1. The aggregate ARTIST L0 and GENRE L1
counts happen to equal device counts because a separate `9th Wonder`
grouping difference offsets the extra Indelible node; ARTIST order still
differs first at position 83. Equal totals therefore do not mean the hierarchy
matches.

The report's key bytes for the pair were computed by a separate Python
translation of the stub, not dumped from the QEMU harness: `Indelible` ends
in `...65 00`, while `Indelible’` becomes `...65 01` under replacement with
`NUL_ZERO`; those candidate keys differ at byte 9. This explains the stub's
split but does not establish the device converter's behavior. The local
seedless `NUL_ZERO` result below is a different input/order condition and must
not be used as a substitute for this seeded run.

As a control, an independent full seeded run mapped every out-of-range unit to
NUL (`MODE_REPLACE`, `FILL=0`, `NUL_ZERO`). It made records 3924/3925 equal,
but overmerged other names: ALBUM had 548 nodes, ARTIST 362/706, and GENRE
30/438/708, versus device counts 549, 363/707, and 30/439/709. ARTIST order
remained 3,944/4,000 with its first difference at position 83 (`9th Wonder`);
the other three order tables were 4,000/4,000. This rejects the broad
“all unsupported characters become NUL” rule and motivates testing only a
terminal unsupported unit specially.

#### Seeded terminal-unit candidate, stock MIPS under QEMU, 2026-11-18

To distinguish “all out-of-range units become NUL” from a narrower rule, the
QEMU harness was run on the same seeded 4,000-song fixture with this candidate
conversion behavior: an out-of-range UTF-16 unit immediately followed by
UTF-16 NUL becomes NUL and ends conversion; any other out-of-range unit becomes
`0x01` and conversion continues. The converter leaves bytes after the
terminator untouched. This is an explicit test hypothesis, not recovered
firmware behavior. The harness exposes it as `TERMINAL_OOR_ZERO`, used with
`MODE_REPLACE` and `FILL=1`; `NUL_ZERO` is not enabled.

The seed was reconstructed from device `MUSIC.LIB`
(`e386db27a9f47641e8755e5a58a903e6e66f83afe2be84c97d5d7e59de0f3c88`) by
replacing each record's 64-byte title field with its zero-padded four-digit
position in the captured ALLSONG order, followed by NUL padding. The fixture
SHA-256 was
`86752ed323f2e3545a1a83a7994ab7df7f11b55755b7192f32176a9d877b7833`.
The stock input was the 2025-06-26 `playlist.ap` with SHA-256
`7b1971f1496318a9682b0abc7681c058ded702aa533c97a32e428f5088b77b64`.

The real stock MIPS16 passes reproduced every indexed result in the fixture:
all four order tables were 4,000/4,000; ALBUM nodes were 549/549; ARTIST
nodes were 363/363 and 707/707; GENRE nodes were 30/30, 439/439, and 709/709.
All per-song inverse-order and node-index tables also matched 4,000/4,000.
This includes both the merged `Indelible` / `Indelible’` boundary and the
`9th Wonder` ordering that differed in the `NUL_ZERO` run. The harness output
is exactly 1,161,216 bytes (`0x11b800`, 2,268 sectors): it contains the input
prefix and generated index data through sector 2267, then ends. The 2,625,536-
byte seed is longer because it also contains the original file tail. The
comparison first differs at `0x11b800`
because the harness does not emit that tail; this is the output boundary, not
an index mismatch.

The contrasting two-record key probes support the mechanism: `FILL=1` leaves
the Indelible keys different at byte 9, while the terminal-unit rule makes
them equal. For records 27 and 67 (`9th Wonder`), stale-tail output preserves
the suffix difference between the plain and UTF-16 fields; `NUL_ZERO` erases
it. The full seeded run shows that preserving this tail while special-casing
only a terminal out-of-range unit is sufficient to match the captured index
tables. It does not show that the device implements this exact rule; another
converter behavior could produce the same keys for this corpus.

The candidate's two branches have unequal corpus support. A scan of the device
library found out-of-range units immediately before NUL in 4 title fields, 2
ARTIST fields, and no ALBUM or GENRE fields. The two ARTIST entries are the
Indelible pair (zero-based record indices 3924/3925; one-based record numbers
3925/3926). The scan found nonterminal out-of-range units in 43 title fields
and 52 ALBUM fields, and none in ARTIST or GENRE. The seeded fixture replaces
all title fields with ASCII ranks, so this experiment does not test the title
cases. The exact ALBUM order, 549 nodes, and per-song tables match despite its
52 nonterminal cases, which gives the `0x01`-and-continue branch broader support
than the terminal-NUL branch. The latter is based on only the pair it was
introduced to explain; treat it as a possible overfit until tested on another
terminal code point or another device library.

Reproduction, with the seeded fixture and firmware inputs mounted read-only
and a disposable work directory:

```sh
python3 tools/library/seed_all_song_order_fixture.py \
  <device-MUSIC.LIB> <seeded-MUSIC.LIB>

CROSS=mipsel-linux-gnu- QEMU=qemu-mipsel-static \
  HEXTRA='-DMODE_REPLACE -DFILL=1 -DTERMINAL_OOR_ZERO' \
  tools/library/test.sh <stock-playlist.ap> <seeded-MUSIC.LIB> <workdir> stock
```

The fixture generator was checked against the available 2026-11-18 seed and
reproduced its SHA-256 exactly.

The full run used `m30-qemu-tools:debian-stable`. The repository harness and
this result are now retained here; the generated ELF and output library are
not required to understand the finding.

#### ARTIST tie-group prior-rank experiment, 2026-11-18

A focused scratchpad QEMU run passed the 56-record ARTIST tie group through
stock `FUN_71812874` and `FUN_40114d54`, using the device's preceding ALBUM
ranks as the input order. The two records that distinguish the observed
device order (record 27 at position 0 and record 67 at position 15) entered
the run at positions 41 and 0 respectively. Their 16-byte keys compared equal
under the harness converter. After sorting and refinement, they were at
positions 41 and 28; the resulting order matched the device in 0/56 positions.

This rules out the preceding ALBUM order, by itself, as an explanation for
the ARTIST tie-group order under this converter hypothesis. It does not prove
the device's converter or tie policy. Inputs were the same hash-verified
`MUSIC.LIB` and `playlist.ap` listed above. The temporary harness, manifest,
and output are in `/private/tmp/m30-tie-qemu/`; the run is not yet reproduced
by a repository command.

#### Local seedless QEMU comparison with replacement conversion

The repository QEMU harness was then run with `MODE_REPLACE`, `FILL=1`, and
`NUL_ZERO` against the same hash-verified stock `playlist.ap` and device
`MUSIC.LIB`. The harness supplies keys and ranks through its own stock pass
sequence; it does not seed the captured device ALLSONG ranks used above. The
only harness change is a compile-time test stub in `tools/library/harness.c`;
the default converter hypothesis is unchanged.

The seedless run produced order scores `ALLSONG=4000/4000`,
`ALBUM=4000/4000`, `ARTIST=3944/4000`, and `GENRE=4000/4000`. It emitted
548 ALBUM nodes, 363/706 ARTIST nodes, and 30/439/708 GENRE nodes, compared
with 549, 363/707, and 30/439/709 in the device file. The ARTIST order
mismatch is the 56-entry `9th Wonder` group; the missing ALBUM/ARTIST/GENRE
nodes are consistent with the remaining equality boundary but do not isolate
the Indelible pair under device-seeded ranks. This result narrows the
seedless converter candidate; it does not establish the converter or resolve
the seeded Indelible/Indelible’ difference.

Reproduction used the local `m30-qemu-tools:debian-stable` image, mounted the
repository, stock firmware, and evidence directory read-only, and wrote only
to a temporary work directory:

```sh
CROSS=mipsel-linux-gnu- QEMU=qemu-mipsel-static \
  HEXTRA='-DMODE_REPLACE -DFILL=1 -DNUL_ZERO' \
  tools/library/test.sh <stock-playlist.ap> <device-MUSIC.LIB> <workdir> stock
```

The inputs were stock `playlist.ap` SHA-256
`7b1971f1496318a9682b0abc7681c058ded702aa533c97a32e428f5088b77b64` and
device `MUSIC.LIB` SHA-256
`e386db27a9f47641e8755e5a58a903e6e66f83afe2be84c97d5d7e59de0f3c88`.

With the unmodified device library, the full stock pass sequence matched the
stored orders at `ALLSONG=3963/4000`, `ALBUM=3973/4000`, `ARTIST=3992/4000`,
and `GENRE=3992/4000`. ALBUM had 547/549 identical nodes; all ARTIST and GENRE
nodes matched. Their reference-5 tables also matched completely. Later passes
consume ranks generated by earlier passes, so these full-sequence scores mix
the section comparator with preceding-order differences.

A no-`HEXTRA` seedless rerun on 2026-11-19 reproduced these order scores and
node-table results. Its complete output and generated-output hash are
preserved in
[`docs/evidence/musiclib-seedless-stock-2026-11-19.md`](evidence/musiclib-seedless-stock-2026-11-19.md).

To isolate that dependency, a temporary copy of the library had only its
64-byte title fields replaced with zero-padded decimal ranks from the captured
ALLSONG order. This makes the stock title pass reproduce the captured order
exactly while leaving ALBUM, ARTIST, GENRE fields and stored index tables
unchanged. The resulting stock pass matched `ALLSONG=4000/4000`,
`ALBUM=3981/4000`, `ARTIST=4000/4000`, and `GENRE=4000/4000`. ARTIST's 363 and
707 nodes, GENRE's 30, 439, and 709 nodes, and both sections' reference-5
tables matched exactly. ALBUM retained only the 19-record `Dünya'dan
Kovuldu`/`Duality` boundary, including its two differing nodes. This confirms
the stock ARTIST and GENRE ordering and node construction against the M30
image once the ALLSONG rank is controlled; it does not prove the remaining
ALBUM conversion behavior.

#### QEMU stop-zero converter comparison

The QEMU harness now supports a diagnostic `TEXT_CONVERSION_STOP_ZERO`
compile-time option. The default still preserves bytes after the first
non-byte UTF-16 character or NUL; the option clears the output tail in those
cases. Both variants were run against the same `playlist.ap` and M30
`MUSIC.LIB`, with evidence mounts read-only:

```sh
CROSS=mipsel-linux-gnu- QEMU=qemu-mipsel-static \
  tools/library/test.sh <playlist.ap> <MUSIC.LIB> <workdir> stock

CROSS=mipsel-linux-gnu- QEMU=qemu-mipsel-static \
  HEXTRA=-DTEXT_CONVERSION_STOP_ZERO \
  tools/library/test.sh <playlist.ap> <MUSIC.LIB> <workdir> stock
```

The default run reproduced the previously recorded order scores
`3963/3973/3992/3992`; ARTIST and GENRE node counts and nodes matched the
device. The cleared-tail run scored `3994/3998/3942/3998`, but emitted only
548 ALBUM nodes, 362/706 ARTIST nodes, and 30/438/708 GENRE nodes, versus the
device's 549, 363/707, and 30/439/709. In the `9th Wonder` run, the device has
a 15-song node starting at record 27, while the cleared-tail run joins the
visible-name blocks into an 81-song node starting at record 67. Thus the
order-only score improves while the observed grouping structure gets worse.

This is direct execution of the stock MIPS passes with two different harness
stubs; it is not execution of the resident text service. It rules out
`stop-zero` as a sufficient global behavior for the observed node structure,
but does not prove the service always preserves stale bytes. Keep both modes
experimental until the scratch-buffer state or resident output is observed.

The harness's whole-file byte comparison reports about 1.44 million differing
bytes, but this is mostly the preallocated file tail: the device image is 5128
sectors while generated index data ends at sector 2268, and the 1,464,320-byte
tail contains 1,444,243 nonzero bytes. The harness starts that unused region
as zero. Use the per-section `libdiff.py` counts above for index comparison;
the whole-file failure is not evidence of 1.44 million bad index bytes.

`QUIRK=2` and `QUIRK=3` produced the same section results as the default
harness; `QUIRK=1` severely corrupted the generated tables. A seedless
`QUIRK=4` rerun also produced byte-identical output to the default run. Its
4 KiB-crossing counter was zero, so the modeled 64 KiB cluster-crossing branch
was never reached. These sector-I/O variants do not explain the remaining
ALBUM boundary. The exact rerun hash and scope are recorded in
[`docs/evidence/musiclib-seedless-stock-2026-11-19.md`](evidence/musiclib-seedless-stock-2026-11-19.md).
No experimental conversion mode has been promoted into the emulator.

Source: [xleph/MP3.LIB](https://github.com/xleph/MP3.LIB), especially its
[README](https://github.com/xleph/MP3.LIB/blob/main/README.md) and public
[MUSIC.LIB member](https://github.com/xleph/MP3.LIB/blob/main/MUSIC.LIB).

The raw member was downloaded from
<https://raw.githubusercontent.com/xleph/MP3.LIB/main/MUSIC.LIB> and is
kept outside the repository at
`<persistent-evidence-root>/MUSIC.LIB`. Its SHA-256 is
`cc3f476c8edb50ea8493b726d46ac82b139a6e87a86cc04c2cdbc30fd3fb8afe`.
Passing this image through `SdCardBlockDevice`, `read_library_layout()`, and
`interpret_library_section_index()` confirms the 251-record header/record
boundary and the section node spans without copying the binary into Git.
The reusable command-line check is
`tools/emu/musiclib_evidence_probe.py`; it verifies the hash before parsing
and prints the per-level record coverage for ALBUM, ARTIST, and GENRE.

The comparison binary was used only as a temporary local hex reference. It is
not stored in this repository.

## Emulator boundary

`tools/sim/library_file.py` keeps `read_library_section_nodes()` as a raw
16-byte reader and adds `decode_library_section_node()` for the field layout.
This preserves unknown bytes and lets callers opt into the recovered fields.
`interpret_library_section_index()` now maps each validated node's song span
back to the raw record indices in the section order table. It validates the
order, inverse, node-index, node-link, and coverage contracts first, then
returns a read-only view; it does not rewrite tables or assign user-facing
folder/album/artist semantics.

Confidence is high for the byte order, field widths, and offsets because the
local builder and the independent hex sample agree. Confidence is medium for
the product-specific meaning of each link and index, and low for assuming the
local sort/group implementation applies to every player, because the
comparison file is not proven to be from an M30 and its derived directory
counts differ from the local model.

The emulator therefore does not yet infer a user-facing tree, resolve node
indices to directories, or assume that `node_type=1` has a broader meaning
than the observed builder contract. It also does not reuse the public sample
to change sorting or grouping behavior.

## Open TODOs and educated guesses

These are investigation targets, not emulator contracts:

| Bytes or behavior | Educated guess | Confidence | What would confirm or reject it |
|---|---|---|---|
| `0xa9..0xf1` | A fixed-width primary file-location or storage-metadata blob. Its size and position between tag metadata and the track field make a path, cluster, or locator representation plausible, but no current consumer decodes it. | Low | An M30-created record paired with its source path and a second record whose path differs in only one component. |
| `0xf4..0xfb` | A little-endian `cluster_no` followed by a little-endian `dir_entry`, matching the US212A `plist_f_info_t` documentation and the firmware's eight-byte lookup key. | Medium–high | A paired M30 directory dump or relocation experiment that independently reproduces both words. |
| `0xfc..0xff` | Per-record predecessor/successor links for source or scan order, separate from section-node links. The placement at the record tail and 16-bit width support this, but no reader has been traced. | Low–medium | Records reordered on disk while preserving metadata, or a traced playback/browser consumer reading these fields. |
| Section table reference `5` | A per-record index into the deepest node level for the section. The recovered `tables()` comment and code use the final run level: album groups for `ALBUM`, the deepest album groups for `ARTIST`, and genre groups for `GENRE`. The level-zero artist group is separately carried through scratch state for the next pass. | High; validated against the captured M30 image | The 4,000-record M30 image's `ALBUM`, `ARTIST`, and `GENRE` values match the deepest-node spans exactly. UI-facing meaning may still differ. |
| Nested run model | ALBUM, then ARTIST, then GENRE nodes are hierarchical runs rather than independent flat groups. The recovered builder and the captured M30 node links, song counts, and table values agree. | High for serialized structure | The captured image validates all child ranges, parent links, level depths, node spans, and per-record deepest-node indices. |

### Captured M30 index validation

The read-only `interpret_library_section_index()` checks were run against
`<persistent-evidence-root>/device-sd/MUSIC.LIB` (SHA-256
`e386db27a9f47641e8755e5a58a903e6e66f83afe2be84c97d5d7e59de0f3c88`). All
four sections passed order-permutation and inverse-table checks. `ALBUM`,
`ARTIST`, and `GENRE` also passed node sibling/parent/child-link checks, node
song-span coverage, and reference-5 mapping to the deepest node indicated by
the serialized runs. `ALLSONG` has no nodes or reference-5 table, so those
structural checks do not apply to it.

The captured M30 node depth bytes are uniform by level: `ALBUM=(1)`,
`ARTIST=(2,1)`, and `GENRE=(3,2,1)`. The host validator now requires each
present node's depth to equal `populated_level_count - zero_based_level`.
This rule is verified against this captured image and the recovered node
layout; it does not establish compatibility with unexamined firmware variants
or arbitrary third-party `.LIB` files.

Observed node counts are `ALLSONG=(0,0,0)`, `ALBUM=(549,0,0)`,
`ARTIST=(363,707,0)`, and `GENRE=(30,439,709)`. `ALLSONG` has valid order and
inverse tables but no node-index table. This confirms the serialized hierarchy
and table roles on an actual M30 library; it does not establish how the UI
labels or navigates those nodes.

The next evidence pass should therefore pair the decoded
`cluster_no`/`dir_entry` values with an M30 directory dump. The primary
location blob and record links remain opaque, and the candidate run model
remains read-only.

The current host tests do exercise the complete synthetic SD path: a
sector-backed image is loaded through `SdCardBlockDevice`, a record is read
through `read_library_record()`, and the decoded secondary key plus opaque
primary/tail values are checked for lossless transport. This verifies the
emulator's byte path, not the real-world meaning of the unresolved values.

`tools/sim/library_index_sort.py` now exposes the decompiled comparison as a
candidate order-only model: ASCII-only upper-casing, signed album track-byte
tie-breaking, and previous-section rank tie-breaking. It operates on raw
256-byte records and never writes an index or claims that its order is the
M30 device's final UI policy. This separation is intentional because the
public comparison file produces different grouping counts from the local
model.

### Seedless stock MIPS16 index pass

The stock `playlist.ap` index passes were run under QEMU against the original
device library, without `seed_all_song_order_fixture.py`:

- `playlist.ap`: SHA-256
  `7b1971f1496318a9682b0abc7681c058ded702aa533c97a32e428f5088b77b64`
  (stock, 2025-06-26).
- Device `MUSIC.LIB`: SHA-256
  `e386db27a9f47641e8755e5a58a903e6e66f83afe2be84c97d5d7e59de0f3c88`.
- QEMU output `out_stock.lib`: SHA-256
  `ff518da115e2a748b72bc130a15825d5d2ad5b0e44d592cebb3c01782c695546`.

The run reproduced all 4,000 records and the section counts. `ARTIST` node
records matched 363/363 at level 0 and 707/707 at level 1; `GENRE` matched
30/30, 439/439, and 709/709 at all three levels. Their per-record node-index
tables matched 4,000/4,000. Thus the earlier seeded-run Indelible grouping
difference does not recur in this unseeded run. This narrows that discrepancy
to a seed-dependent result; it does not identify the real text converter.

Other index data still differs: `ALLSONG` order/inverse matched 3,963/4,000;
`ALBUM` node records matched 547/549 and its order/inverse matched
3,973/4,000; `ARTIST` and `GENRE` order/inverse each matched 3,992/4,000.
The first `ALLSONG` ordering difference is between the same base title with
`.flac` and `.wav` extensions. The full comparison is recorded in
`analysis/local-evidence/musiclib-seedless/run/libdiff_stock.txt`; the harness
log is in `analysis/local-evidence/musiclib-seedless/run.log`.

Important limit: the stock MIPS16 passes ran, but module 6 / function `0x1a`
text conversion was a harness stub using its default stop-at-wide-character
behavior. This validates the observed node boundaries under that stub, not
the device converter's semantics. The full-file comparison reports `FAIL`
because ordering, album nodes, and file extent differ; this was not a
byte-for-byte reproduction.

#### Seedless stock run with replacement text conversion, 2026-11-19

To check whether the default stop-at-wide-character stub caused the seedless
order differences, the same unmodified device library was run through the
stock MIPS16 passes with `MODE_REPLACE` and `FILL=1`. The stub maps every
out-of-range UTF-16 code unit to `0x01` and continues conversion; it leaves the
post-NUL tail untouched. This is a broad converter hypothesis, not the
resident converter. The Docker command used the stock 2025-06-26
`playlist.ap` (`7b1971f1496318a9682b0abc7681c058ded702aa533c97a32e428f5088b77b64`)
and device `MUSIC.LIB`
(`e386db27a9f47641e8755e5a58a903e6e66f83afe2be84c97d5d7e59de0f3c88`), with
no seed fixture.

All four order and inverse-order tables matched the device at 4,000/4,000.
The per-record node-index tables present for ALBUM, ARTIST, and GENRE also
matched 4,000/4,000. ALBUM nodes matched 549/549. ARTIST had 364 level-0
nodes instead of 363; its level-1 count remained 707. GENRE had 440 level-1
nodes instead of 439, while its level-0 and level-2 counts remained 30 and
709. Node bytes in the affected ARTIST and GENRE levels did not all match.
Both extra nodes come from
the device-merged `Indelible` / `Indelible’` pair, which this broad replacement
stub keeps separate. The generated header and node bytes therefore still
differ; this is not a full-file match.

This run changes the earlier seedless ALLSONG result: the first difference at
position 65 (`01. Dokunmayarayaparsın.flac` versus the corresponding `.wav`)
is gone, and the complete order table now matches. Both raw UTF-16 title
fields contain U+0131 before their extensions. That observation makes the
stop-at-U+0131 boundary a plausible contributor to the old ordering result,
but the experiment changed conversion for every unsupported code unit, so it
does not isolate U+0131 or prove the device's conversion rule.

The output `out_stock.lib` SHA-256 is
`319d09bf87ca5b3e308f910e77e8331f094dff3a428cd15fbf3520f407525dc9`.
The full section comparison and harness log are retained in the ignored local
evidence directory at
`analysis/local-evidence/musiclib-u0131-replace/libdiff_stock.txt` and
`analysis/local-evidence/musiclib-u0131-replace/harness_stock.txt`; the output
library is also retained there. The run used:

```sh
HEXTRA='-DMODE_REPLACE -DFILL=1' \
  tools/library/test.sh <stock-playlist.ap> <device-MUSIC.LIB> \
  <workdir> stock
```

Interpretation: the stock index pass plus this replacement stub explains the
seedless ordering and per-record index tables, while one narrow node-grouping
discrepancy remains. Treat the converter behavior as a useful candidate for
further comparison, not as recovered firmware behavior.

#### Seedless U+0131-only conversion control, 2026-11-19

A second stock MIPS16 run changed only UTF-16 U+0131 to `0x01` and continued;
other out-of-range units retained the default stop-at-wide behavior. This
selective harness mode is `REPLACE_ONLY_CODEPOINT=0x0131` with `FILL=1`. It uses the same
hash-pinned stock `playlist.ap` and original device library listed above, with
no seed fixture.

The device header stayed identical. ALLSONG order/inverse remained 3,963/4,000;
its first mismatch moved from position 65 to position 684. ALBUM order/inverse
matched 3,971/4,000 and its node-index table 3,981/4,000. ARTIST and GENRE
order/inverse each matched 3,990/4,000, while their node bytes matched exactly:
ARTIST 363/363 and 707/707, GENRE 30/30, 439/439, and 709/709. Their node-index
tables matched 4,000/4,000. The `Indelible` / `Indelible’` grouping matches
the device under this control, which leaves terminal U+2019 on the default
stop path; the broad replacement run instead split it. The full output still
differs in ordering and ALBUM nodes.

This separates two observations: broad replacement of every unsupported unit
was sufficient for all four order tables to match in the prior run, but
replacing terminal U+2019 with `0x01` split the Indelible group. This control
changed U+0131 only and retained the default stop behavior for U+2019. It
supports preserving terminal U+2019 behavior rather than applying the broad
replacement to it; it does not distinguish “stop and retain tail” from
“treat terminal unit as NUL,” nor prove the resident converter's U+0131
behavior. The control changes U+0131 globally, not only in the Turkish title
that exposed the earlier order difference.

The selective-run output `out_stock.lib` SHA-256 is
`0898204750efcb041983e529a78c21ad6acbe1d661fc113a9fa0f256e8c50e4d`. Its
comparison and harness log are retained under
`analysis/local-evidence/musiclib-u0131-only/`. Reproduce with:

```sh
HEXTRA='-DMODE_REPLACE -DREPLACE_ONLY_CODEPOINT=0x0131 -DFILL=1' \
  tools/library/test.sh <stock-playlist.ap> <device-MUSIC.LIB> \
  <workdir> stock
```

#### Seedless U+2019-only conversion control, 2026-11-19

A separate stock MIPS16 run changed only UTF-16 U+2019 to `0x01` and continued;
other unsupported units retained the default stop-at-wide behavior. It used
`REPLACE_ONLY_CODEPOINT=0x2019` and `FILL=1`, with the same stock
`playlist.ap` (SHA-256
`184f2c201537e6657024a2a5a054d12fe4a7f134e06205f0786a8d71d2a29403`) and
device `MUSIC.LIB` (SHA-256
`e386db27a9f47641e8755e5a58a903e6e66f83afe2be84c97d5d7e59de0f3c88`). No
seed fixture was used.

This control does **not** reproduce device grouping. ARTIST has 364 level-0
nodes instead of 363, and GENRE has 440 level-1 nodes instead of 439. The
`Indelible` / `Indelible’` pair is split in both sections, as in broad
replacement. ALLSONG order matches 3,963/4,000; ALBUM order matches
3,973/4,000; ARTIST and GENRE order each match 3,992/4,000. ARTIST and GENRE
per-record node-index tables still match 4,000/4,000, while ALBUM's matches
3,981/4,000. Thus replacing U+2019 alone is not sufficient to explain the
device's merge, and it also does not explain the remaining sort differences.

The `out_stock.lib` SHA-256 is
`29c2a4f36aed3c9aebaeb659c82484c8ec201d8d0b22acf1c788693e2e146f63`; the
run log, comparison, and generated output are retained locally under
`analysis/local-evidence/musiclib-u2019-only-rerun/`. Reproduce with:

```sh
HEXTRA='-DMODE_REPLACE -DREPLACE_ONLY_CODEPOINT=0x2019 -DFILL=1' \
  tools/library/test.sh <stock-playlist.ap> <device-MUSIC.LIB> \
  <workdir> stock
```

This weakens the narrow “U+2019 maps to `0x01`” explanation. It does not
recover the resident converter; the terminal-out-of-range-as-NUL candidate
still matches the indexed tables for this one library, and may be corpus-
specific.

#### Seedless terminal-out-of-range-as-NUL candidate, 2026-11-19

The final stock MIPS16 run combined the two branches suggested by the controls:
an out-of-range UTF-16 unit followed immediately by UTF-16 NUL writes NUL and
stops conversion; other out-of-range units become `0x01` and conversion
continues. The harness leaves bytes after the resulting NUL untouched. This
is still a test converter, not recovered firmware code. It used the same
hash-pinned stock `playlist.ap` and original device `MUSIC.LIB`, with no
seed fixture.

This candidate reproduced every indexed table in the original 4,000-record
library: all four order and inverse-order tables matched 4,000/4,000; ALBUM
nodes matched 549/549; ARTIST nodes matched 363/363 and 707/707; GENRE nodes
matched 30/30, 439/439, and 709/709. The ALBUM, ARTIST, and GENRE per-record
node-index tables also matched 4,000/4,000. The reported output file is
1,161,216 bytes (`0x11b800`, 2,268 sectors); comparison first differs at
`0x11b800`, exactly where this harness output ends and the longer device file
continues. Thus the indexed structures match; the full file is not reproduced.

The output `out_stock.lib` SHA-256 is
`59f64e94dfb07de28a363ea2123ee45d230549129de7f77198876a584a724865`. The
comparison and harness log are retained in
`analysis/local-evidence/musiclib-terminal-oor-zero/`. Reproduce with:

```sh
HEXTRA='-DMODE_REPLACE -DTERMINAL_OOR_ZERO -DFILL=1' \
  tools/library/test.sh <stock-playlist.ap> <device-MUSIC.LIB> \
  <workdir> stock
```

This is the strongest current host-side explanation of the device's indexed
results: broad replacement accounts for the sort tables, while treating the
terminal unsupported unit specially preserves the Indelible grouping. It
matches one complete device library's index tables but does not identify the
resident converter; another implementation could yield the same output for
this corpus. Keep the converter semantics labeled as a hypothesis.

#### Public 251-record MUSIC.LIB cross-check, 2026-11-19

A previously documented public 1,536,000-byte, 251-record `MUSIC.LIB` was
reused from
`/Users/berkan/Projects/mechen-m30-evidence/MUSIC.LIB` (SHA-256
`cc3f476c8edb50ea8493b726d46ac82b139a6e87a86cc04c2cdbc30fd3fb8afe`). Its
hash and size differ from the 4,000-record device-card image above. Its public
origin does not establish that it came from a physical M30, so this remains a
format-level cross-check, not independent device evidence.

Running the same stock MIPS16 indexer with `MODE_REPLACE`,
`TERMINAL_OOR_ZERO`, and `FILL=1` reproduced the ALLSONG and ALBUM order and
inverse tables (251/251); ALBUM nodes and its per-record node-index table also
matched (1/1 and 251/251). ARTIST and GENRE did not match: ARTIST order and
inverse tables matched 219/251; its node counts were `(2, 2, 0)` versus
`(1, 1, 0)` in the input. GENRE order and inverse tables also matched
219/251; its level counts were `(1, 2, 2)` versus `(1, 1, 1)` in the input.
Their node-index tables matched 250/251. The generated output hash is
`a6d15d9b6fb9f8c3bade6f9961fd74cd902ef92e42c703514dae67288ae370d2`. The raw
harness and detailed comparison outputs, plus input hashes and run parameters,
are preserved in
[`docs/evidence/musiclib-terminal-oor-zero-second-lib/`](evidence/musiclib-terminal-oor-zero-second-lib/README.md).

This does not refute the candidate against the 4,000-record device image, and
the second file is not known to be a device-generated index. It does show that
the candidate does not reproduce this other file's ARTIST/GENRE tables, so the
first exact match cannot be generalized to arbitrary M30-format libraries.
Reproduce with:

```sh
HEXTRA='-DMODE_REPLACE -DTERMINAL_OOR_ZERO -DFILL=1' \
  tools/library/test.sh <stock-playlist.ap> <second-MUSIC.LIB> \
  <workdir> stock
```

#### User SD-card 4,000-record cross-check, 2026-11-24

A read-only stock MIPS16 run used the user's mounted SD-card library, a
separate 2,625,536-byte file with SHA-256
`02c10ceb6463d60201e531757350999d52d8bf6d66614a2183f13642fca8d017`, and
the same stock `playlist.ap` hash as the seedless run. With
`MODE_REPLACE`, `TERMINAL_OOR_ZERO`, and `FILL=1`, all four order and inverse
tables matched 4,000/4,000. ALBUM nodes matched 549/549; ARTIST nodes matched
359/359 and 703/703; GENRE nodes matched 31/31, 436/436, and 706/706. All
three per-record node-index tables also matched 4,000/4,000.

The generated file is 1,160,704 bytes, and the reported first file difference
is exactly its end (`0x11b600`); the harness emits index data, not the
remaining library records and trailing sectors. The raw outputs and run
details are preserved in
[`docs/evidence/musiclib-sd-card-stock-2026-11-24/`](evidence/musiclib-sd-card-stock-2026-11-24/README.md).

This is a second independent 4,000-record input whose indexed tables match
under the candidate conversion stub. It strengthens the corpus-level fit but
does not prove the resident text converter uses this rule or that the rule
generalizes; the public 251-record cross-check above still diverges in
ARTIST/GENRE.

The same module now derives the candidate nested run lengths that feed the
three node levels in the local builder. `ALBUM` produces its album runs;
`ARTIST` uses those album group indices as its child grouping; and `GENRE`
uses artist groups as its first child level and album groups as its second.
`group_indices_from_runs()` converts each run list to a per-record group
index. The implementation validates permutation, coverage, and positive run
lengths, but it remains a host-side reconstruction helper: it does not write
node bytes, replace parsed tables, or assert that M30 creates the same groups.

`validate_library_section_nodes()` checks only the recovered wire contract:
non-zero node type/depth and song counts, record bounds, same-level
previous/next sentinels, parent/child reciprocity, contiguous child ranges,
depth relationships, and per-level song coverage. The public 251-record
sample passes this check for all four sections. A multi-group M30 image is
still required before these links are used as a user-facing hierarchy.

## Next evidence target

Compare an M30-created `MUSIC.LIB` byte image against the existing raw reader
and this decoder. The highest-value checks are the section reference order,
node link sentinels, and whether `first_record_index` points into the same
record index space. Until then, new behavior should remain behind the decoder
boundary rather than being presented as M30-specific behavior.
