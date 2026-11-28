# V1.101.11 `music.ap` metadata-reader delta

This note classifies the two non-EOF changes that V1.101.11 shares with the
community V1.292.00 `music.ap`. It is static evidence from the unpacked AP
members; it is not an on-device audio or artwork timing measurement.

## The large replacement: a shared metadata/file reader

The stock bytes at file offset `0x120c0` through `0x12193` are replaced by
212 bytes in V1.101.11 and community. The bank table maps this area to
`0x781db200`, so the changed code begins at runtime `0x781db3c0`,
`FUN_781db3c0`.

Ghidra decompilation shows the behavioral boundary:

- stock clamps the requested amount once, performs at most one `0x200`-byte
  boundary split, and advances the total by the original `param_3`;
- V1.101.11/community loop while bytes remain, split every `0x200` boundary,
  update the buffer position and total by the bytes actually copied, flush at
  each full boundary, and call the existing skip helper for a trailing
  `param_3 - param_2` range.

The function has many direct callers in the metadata path, including
`FUN_714125ac` (ID3/tag scanning), `FUN_714d25b8`/`FUN_714d266c` (FLAC
metadata/Picture block scanning), `FUN_715125c4` (M3U-like metadata), and
`FUN_71612644` (bounded text extraction). This is therefore a strong
metadata/artwork parsing fix for reads that cross the internal 512-byte
buffer boundary. It is not evidence that the decoder, ASRC, or DAC path was
changed, and it should not be presented as a second explanation for the
Audacity fade-out tail.

The host model records the narrow copy boundary in
`tools/sim/metadata_reader.py`: a request is split at every `0x200` boundary
and a chunk ending at that boundary is marked for the proven flush point. It
also records the proven `total_bytes - copied_bytes` trailing-skip arithmetic.
`read_metadata_chunks()` applies those boundaries to a concrete source byte
string; the skip callback, source callback ABI, and metadata interpretation stay
outside the model.

## The small sibling change

Three instruction bytes at file offset `0x115cc` change from `e1 26 25` to
`11 25 75`. The bank table maps the location to `0x7815b5cc`, where Ghidra
creates `FUN_7815b5cc` but reports bad instruction data and no direct call
references. The surrounding function boundary is not reliable enough to
assign a purpose. The probe records the exact delta but intentionally leaves
it opaque; it is not a standalone patch candidate.

## Reproduction

```sh
bash tools/ghidra/run_music_v10111_delta_probe.sh
```

The probe compares stock, V1.101.11, and community images, verifies the raw
bytes, and checks the decompiled loop and its metadata callers. The accepted
ASRC/EOF branch remains separately covered by
`tools/ghidra/run_asrc_probe.sh`.
