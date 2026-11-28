# MUSIC.LIB record location bytes

This note separates the M30 record layout recovered from the firmware from
the structurally similar public comparison file.

## What the M30 index builder actually consumes

`tools/library/index.c` reads each 256-byte record during `extract()` and
uses these fields for index construction:

- title: `0x00..0x3f`
- album: `0x58..0x87`
- artist: `0x40..0x57`
- genre: `0x88..0x9f`
- numeric track byte: `0xf2` (the low byte is copied into the sort key)

The builder converts the four text fields into scratch keys, carries the
track byte through the sort key, and writes only derived scratch tables and
nodes to the index tail. It does not assign a filesystem or path meaning to
the record location slices. Reading a 256-byte window is not evidence that
every byte in that window is interpreted.

The decompiled `extract()` makes this boundary explicit. For section zero it
loads records in batches, calls `CONVERT()` only for the four text ranges, and
appends `rec[0xf2]` plus a zero pad to the later section keys. For later
sections it reads only those scratch keys, the previous-section rank, and the
group scratch tables. The temporary key layout is documented in the source as
`name[W] | track u8 | pad | rank u16 | group1 u16 | group2 u16 | song u16`;
none of the record-tail location bytes are copied into it. The `0xa8`
`always_one` byte is likewise not consumed by this pass.

The record scan's location write is now traced one step further. In
`FUN_40094f2c`, the firmware calls the filesystem module entry
`0xbfc1640d` (module `0x11`) with operation `3`, passing the primary buffer
at `record + 0xa9` and the secondary buffer at `record + 0xf4` together.
The pointer is resolved from the literal pool, not inferred from the record
bytes. This establishes that both slices are produced by one filesystem
location ABI call; it still does not establish the internal layout of the
73-byte primary result. The repeatable check is
`tools/ghidra/run_library_record_probe.sh`.

The same probe passed against all nine available dated/community `playlist.ap`
members in the repository, including the stock and V1.101.11/V1.101.12
corpora. The callback address and operation number are therefore stable across
the available application versions, rather than a single community-build
artifact.

The host codec preserves the unresolved regions and decodes the supported
secondary file key:

| Range | Host field | Status |
|---|---|---|
| `0xa9..0xf1` | `location_primary` | opaque bytes |
| `0xf2..0xf3` | `track_number` | little-endian numeric field; low byte participates in index sort |
| `0xf4..0xfb` | `location_secondary` | little-endian `cluster_no` + `dir_entry` file key; preserved as raw bytes too |
| `0xfc..0xff` | `previous_index`, `next_index` | decoded record-link candidates; M30 consumer semantics still need tracing |

The host `find_library_record_index()` helper now reproduces the reader-side
lookup boundary: it compares the eight-byte key only on active records
(`+0xa8 == 1`) and stops after the first 4,000 records. The real device image
round-trips samples at indices `0`, `1`, and `3999` through this lookup.

Changing an opaque location field while rebuilding the index is not currently
justified by the decompile. A future editor must preserve those bytes unless
a separate filesystem read/write trace proves their format.

This is stronger negative evidence than a missing symbol reference: the
indexer has a bounded record reader and an explicit scratch-key construction,
yet the location slices never enter either path. Any location interpretation
must therefore come from a different firmware consumer or from controlled
M30-generated card images, not from the index builder itself.

## Reader-side consumer evidence

Ghidra 12.2 was also run against the extracted 2025-06-26 `music.ap` with
`tools/ghidra/run_library_record_consumer_probe.sh`. The relevant consumers
show the following behavior:

- `FUN_712d27f4` walks active records (`record + 0xa8 == 1`) and compares an
  incoming eight-byte file key with `record + 0xf4..0xfb`; a match returns the
  library record index.
- `FUN_71092500` performs the same lookup while loading the current record,
  then copies `+0xf4..0xfb`, `+0xa9..0xf1`, and `+0xa4..0xa7` into its output
  structure.
- `FUN_712d2a9c`, `FUN_71352810`, `FUN_713528ec`, `FUN_717d282c`, and the
  playback-side helper at `FUN_7181291c` use the same eight-byte locator
  boundary; they do not expose a byte order or filesystem structure for the
  primary blob.

The same consumer probe passed against every available dated `music.ap`
member from 2022-09-14 through 2025-06-26. The eight-byte locator boundary and
the negative result for direct `+0xfc`/`+0xfe` reads therefore survive the
available version changes. This is stronger evidence for keeping the record
links lossless-but-opaque in the host emulator; it is not proof that an
indirect ROM helper never reads them.

## Play-counter identity implication

The secondary eight-byte `cluster_no`/`dir_entry` key identifies a filesystem
location, not the audio content. Sorting the library changes index tables and
does not itself move a directory entry, so the key can survive sorting. A
re-index can reproduce it while the file remains at the same entry; moving or
renaming a file may change it, and a deleted entry can later be reused. The
firmware lookup returns the first matching active record, so duplicate keys
would make a sidecar count ambiguous. Identical audio files at distinct
locations remain distinct under this key.

There is no record field proven safe for a persistent play count: the key and
opaque location data are read by firmware, and the record tail stores links
rebuilt by the indexer. A sidecar is therefore the safer design boundary.
For that sidecar, a content digest plus normalized relative path can identify
an unchanged file and distinguish same-content copies; a unique content-digest
match can support recovery after a move. This remains a design proposal, not a
device-validated storage or rename-recovery contract. A temporary host-only
synthetic check covered reorder, move, duplicate content, and duplicate key
cases; it did not model exFAT directory-entry allocation.

The first append-only journal prototype is **not safe to port yet**: if a
`MOVE` frame is corrupted and skipped, the current scanner can apply a later
higher-sequence `SET`, leaving the old path's count visible again. Its existing
corruption test did not check this stale-path resurrection. Recovery must
fail closed at a sequence gap or corrupt transaction, and the corrected rule
needs a regression before the sidecar format is treated as viable.

## US212A SDK correspondence

A publicly mirrored US212A application-design manual documents the same
`plist_f_info_t` family used by the Actions playlist layer. Its 256-byte file
record describes a `cluster_no` followed by a `dir_entry`, both as 32-bit
filesystem location fields; its LIB section header also documents `file_index`,
`sort_index`, and `index_parent` as sector-based tables. The manual is
preserved outside Git at the persistent evidence root with SHA-256
`c6795e5aaef95af5b9bd16d469086e4f69603db87409791f039315705d7d0b05`:

<https://tchumim.com/assets/uploads/files/1622479367338-%D7%9E%D7%93%D7%A8%D7%99%D7%9A-%D7%9C%D7%AA%D7%9B%D7%95%D7%A0%D7%9F-%D7%90%D7%A4%D7%9C%D7%99%D7%A7%D7%A6%D7%99%D7%95%D7%AA-us212a.pdf>

Combined with the Ghidra consumer's eight-byte compare/copy boundary and the
real card's values, this is sufficient to expose the two little-endian words
as `cluster_no` and `dir_entry` in the host codec. The primary
`0xa9..0xf1` directory-layer blob and the `0xfc..0xff` links stay lossless
and unresolved.

The same manual's `plist_f_info_t` declaration explicitly places
`cluster_no`, `dir_entry`, and then `pdir_layer_t dir_layer_info` in the
256-byte playlist record.  This is an external correspondence for the role of
the M30 primary slice, not a byte-for-byte layout proof: the manual does not
provide the definition of `pdir_layer_t`, and the M30 record's tail ordering
and primary size differ.  The M30 primary therefore remains an opaque
directory-layer blob in the codec even though its observed two-layer behavior
now agrees with that SDK vocabulary.

An exhaustive 2025-06-26 `music.ap` decompile also found no direct
`record + 0xfc` or `record + 0xfe` access in these record consumers. This is
useful negative evidence, not proof that an indirect ROM helper never reads
the fields. The preserved decompile hash is
`bf393413491046318d5a985445dcf5eb8a3aeaccb952816143ffcc65d25f9af0`.
The eight-byte secondary locator is now modeled as a little-endian
`cluster_no`/`dir_entry` file-identity key at the boundary, while
`+0xa9..+0xf1` and `+0xfc..+0xff` remain lossless opaque fields until an
actual M30 card image and its directory contents are paired.

## Primary-location correlation on the observed card

The mounted read-only M30 card provides a useful byte-level invariant without
requiring raw-device access.  `tools/emu/library_primary_location_probe.py`
checks all 4,000 records and compares the secondary `cluster_no` against two
32-bit words inside the unresolved primary blob:

| Primary offset | Matches secondary `cluster_no` |
|---|---:|
| `+0x18` | 865 / 4,000 |
| `+0x20` | 3,135 / 4,000 |

The matches are exclusive: no record matches both words, and no record matches
neither.  This is strong evidence that the primary blob contains a directory
or location-layer representation related to the secondary file key.  It is
not yet enough to choose between a directory-layer field, a selected-layer
field, or a format-dependent union, so the host codec continues to preserve
the complete primary blob losslessly.  The same probe also observes constant
32-bit values at primary offsets `+0x28` and `+0x2c` on this card; those values
are recorded as invariants, not assigned meanings.

The next word in whichever pair contains the matching cluster is also
structured: its unsigned difference from the secondary `dir_entry` is a
multiple of `0x20` for all 4,000 records.  On this card those differences span
`0x0..0x8c0`.  This makes an exFAT directory-entry-chain or layer-offset
relationship substantially more plausible than an arbitrary binary token, but
does not identify the exact base entry or layer number.

## Mounted-tree cross-check

The mounted card provides an independent path-level check without raw-device
access.  Matching a record title to a unique filename under the mounted music
tree matched 2,242 of 4,000 records.  Of those, 2,240 records belonged to a
`cluster_no` group that mapped to exactly one parent directory; 404 of the 405
observed cluster groups were single-parent groups.  Multiple songs in a folder
share the cluster while their `dir_entry` values vary in `0x20`-aligned steps.

The non-selected primary cluster word provides a second path-layer signal: in
1,706 of those 2,242 unique matches it equals the mounted inode of the file's
grandparent directory.  This is a mount-level inode cross-check, not raw
exFAT proof, but it is consistent with the primary blob carrying one or more
directory-layer pairs.  Shallower paths do not show the same grandparent
match, and the non-selected pair may instead preserve traversal/sibling state;
the exact layer and entry-offset interpretation therefore remains open.

This strongly favors the interpretation that the secondary eight-byte key is
the containing directory's cluster plus an entry offset, rather than the audio
file's data cluster.  The reproducible read-only check is
`tools/emu/mounted_library_path_probe.py`.  The result still does not decode
the primary blob or prove the exact exFAT entry-set base; the path match is
limited to titles that are also unique filenames.

This result is a high-value next target for a paired card-directory capture:
the primary word that equals `cluster_no` can be compared against the actual
directory chain and file entry.  Until that pairing exists, a semantic
decoder or record rewriter would still be speculative.

The repository's `tools/library/mklib.py` fixture writes
`previous_index=i-1` and `next_index=i+1` while constructing synthetic records.
That makes the linked-record hypothesis useful for test data and explains why
the host codec exposes these two words, but it is local fixture convention,
not independent firmware evidence. In particular, the fixture's terminal
`next_index` behavior must not be promoted to a device sentinel rule.

## Why the public comparison file is not enough

The public `MUSIC.LIB` comparison file matches the broad header, section, and
node geometry, but it is not a valid M30 record-tail fixture. In its first
record, the visible `MP3` marker occurs at a different record offset and the
bytes at the M30 codec's `0xf2` position are not an ASCII track field. It can
corroborate the shared index family, not the M30 record-location layout.

This is why no primary-location decoder or record rewriter has been added to
the host emulator. The current `decode_record()` contract is deliberately
loss-aware: it returns the decoded secondary key and the unresolved slices so
callers can preserve them without inventing semantics.

## Next evidence target

Use an actual M30-created `MUSIC.LIB` together with the corresponding MP3
directory tree. Compare `cluster_no`/`dir_entry` and the opaque primary slice
against file names, sizes, cluster boundaries, and the record links before
assigning further location meaning.
