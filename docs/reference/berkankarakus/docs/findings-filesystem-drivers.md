# M30 FAT32/exFAT filesystem-driver boundaries

This note records the static boundary of `exfatn.drv` and `fat_32n.drv`. It
deliberately separates observations from the resident driver members from
the higher-level library builder in `playlist.ap`. The probe never executes
driver code and does not access a card.

## Reproducible checks

```sh
PYTHONPATH=tools/emu python3 tools/emu/filesystem_binary_probe.py \
  analysis/community/fw/exfatn.drv \
  analysis/community/fw/fat_32n.drv

python3 -m unittest discover -s tools/emu -p 'test_filesystem_binary_probe.py'
bash tools/ghidra/run_filesystem_probe.sh
```

The six extracted firmware trees in this repository contain byte-identical
copies of both members. The probe checks all six copies rather than assuming
that the community member is representative.

## Container boundary

| Member | Resident segment | BSS | Entry field | SHA-256 |
|---|---|---|---|---|
| `exfatn.drv` | file `0x200`, `0x800` bytes → `0xbfc16c00` | `0x9fc18300`, `0xa00` | `0x240094d8` | `6c6b724a86f371cf4cbca7ab5f1b0c970d27ecebd502d3c7edb1e5e895d3c0b5` |
| `fat_32n.drv` | file `0x200`, `0x800` bytes → `0xbfc16c00` | `0x9fc18300`, `0xa00` | `0x230096f4` | `bed63d62aff4df62254b758050abd4ee72f3a829dc39ba374485e64875c75b6b` |

For the Ghidra flat mapping, file offset `0x200` is mapped at runtime
`0xbfc16c00`; therefore file byte zero is represented at `0xbfc16a00`.
Ghidra recovers four MIPS16 functions from `exfatn.drv` and six from
`fat_32n.drv`.

## Extension table and index relevance

Both members contain the same fixed table. The four bytes immediately before
the table are `00 00 ff ff`; the matcher consumes 31 four-byte slots:

```text
MP3 WMA WAV AAC FLAC APE AVI TXT JPG JPEG DSF DFF LRC ASF OGG TTA
ISO AAX AA MP1 MP2 M4A POS ACT BMK AIF APK PCM ** *f "   "
```

The table starts at file offset `0x7f8` in `exfatn.drv` and `0x780` in
`fat_32n.drv`. The decompiled extension-matching functions are:

- exFAT: `FUN_bfc16f90`
- FAT32: `FUN_bfc16d94`

Both loop over the table with a 31-entry bound and use a caller-supplied
bit/mask parameter to decide which table entries are accepted. This is the
filesystem boundary that can include or exclude a file from a caller's walk;
it is therefore relevant to library candidate enumeration. It is not itself
the automatic-index trigger, which remains in the application/configuration
call chain documented in `library-builder.md`.

The table bytes and order are proven. The matcher loop also proves the
driver-side mask convention: slot `0` checks bit `31`, slot `1` checks bit
`30`, and slot `30` checks bit `1`; bit `0` is not consumed by the 31-entry
loop. This is a bit-order fact, not an assignment of product meaning to every
literal mask in `playlist.ap`.

The host emulator exposes this boundary in
`tools/sim/filesystem_extension.py`. It resolves the shared slot vocabulary
case-insensitively, applies explicit slot selections, and exposes the
driver-side high-bit-first mask decoding. It does not infer the caller's
product policy, walk directories, or assign meanings to the wildcard-looking
`**` and `*f` slots.

The caller-side raw masks are checked separately by
`tools/emu/library_mask_probe.py`: `playlist.ap` stores `0x7e190ea0` at file
`0x2a20` and `0x00080400` at file `0x2a28`, with a pointer word between them.
Their raw bit positions and driver slot positions are exposed separately by
the probe/model; the model does not claim that the resulting set is the full
user-facing meaning of either library type.

The application-level extension-to-reader selector is modeled separately in
`tools/sim/library_tag_reader.py`: the recovered types are `AA`, `AAC`,
`M4A/AAX`, `APE`, `FLAC`, `MP1/2/3`, `OGG`, `WMA/ASF/WMV`, and type-8
fallback. Reader ABI and metadata parsing remain outside the model.

The proven fallback record shape is modeled in
`tools/sim/library_record.py`: the 8.3 short name becomes the title,
`No Artist`/`No Album`/`No Genre` are used for the other text fields, and the
tag-success flag remains false. The associated track selector is limited to a
four-digit prefix and uses `0xffff` for a zero result; `tag_ok=false` writes
zero instead.

The 256-byte record layout is exposed by
`tools/sim/library_record_codec.py`, including BOM/ASCII text, track fields,
extension, link indexes, and opaque location slices. It is a byte codec, not a
filesystem or metadata-reader implementation. The Ghidra evidence is
reproducible with `tools/ghidra/run_library_record_probe.sh`.
The raw 8.3-to-`NAME.EXT` formatting boundary is also exposed by
`format_short_name()` in `tools/sim/library_record.py`.

The application walker is represented separately by
`tools/sim/library_walker.py`: it preserves pre-order depth-first traversal,
the maximum depth of 8, and the `RECORD` exclusion from the verified stock
contract. Filesystem callbacks and physical directory state are not emulated.

## Directory enumeration

The dispatch functions are:

- exFAT: `FUN_bfc16e48`
- FAT32: `FUN_bfc16f20`

Both contain explicit branches for command bytes `0x80`, `0x81`, `0x82`, and
`0x83`. The branches reset or advance per-directory state, obtain entry data
through indirect callbacks, and feed candidate names through the extension
matcher. The decompile also exposes two distinct callback selectors, `0x6e`
and `0x70`, used by the traversal path. The exact user-facing names “next
sibling”, “next child”, and “enter directory” are not assigned to individual
opcodes here because the dispatch table and its caller-side state machine are
outside these 0x800-byte members.

The exFAT-specific parser `FUN_bfc16c84` recognizes directory-entry type
bytes and constructs an uppercase three-character extension from the entry
data. It also has an explicit dot-entry check. The corresponding FAT32
entry/data helper is `FUN_bfc16c84` in the FAT32 image; the surrounding
enumeration state machine differs, but the extension table remains identical.

## Open/read/write/seek boundary

No function in either resident segment directly implements the public kernel
file operations (`open`, `read`, `write`, or `seek`) described in the library
builder note. The recovered routines call imported/global function pointers
indirectly. The final functions are logical-position/cluster translation and
cached block-access paths:

- exFAT: `FUN_bfc170e0`
- FAT32: `FUN_bfc17068`

They derive a block address from per-file state, invoke an indirect callback,
and combine bytes from a cached sector/table buffer. This proves a storage
translation boundary, not the exact callback ABI or whether a particular
caller operation is read-only. The public op numbers and handle semantics
must therefore be traced from `kernel.drv`/the application call sites rather
than patched in these members.

## What this closes and what remains

Verified:

- the FAT32 and exFAT residents have the same load size, BSS range, and
  31-slot extension vocabulary across all local firmware versions;
- directory traversal and extension filtering are present in both members;
- the drivers are below the library builder and do not contain its automatic
  update trigger or index-sort implementation;
- the storage path is indirect and cluster/block based, so changing the
  extension table alone would not implement asynchronous indexing.

Still uncertain:

- exact caller-side product policy beyond the driver's high-bit-first matcher;
- exact meanings of the four traversal opcodes in every caller state;
- exact imported callback signatures for file open/close/seek/read/write;
- whether any asynchronous behavior is scheduled above this boundary.

No firmware or HEX patch is proposed from this analysis.
