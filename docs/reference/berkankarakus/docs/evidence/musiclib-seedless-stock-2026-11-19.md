# Seedless stock MUSIC.LIB run (2026-11-19)

## Inputs and command

- Captured device `MUSIC.LIB`: SHA-256
  `e386db27a9f47641e8755e5a58a903e6e66f83afe2be84c97d5d7e59de0f3c88`.
- Stock `playlist.ap`: SHA-256
  `7b1971f1496318a9682b0abc7681c058ded702aa533c97a32e428f5088b77b64`.
- Docker image `m30-qemu-tools:debian-stable`:
  `sha256:0d1902177949ab2164602d535a37fe5227f19760a0570fd766a0c324d0ff0baf`.
- Mode: `stock`; no `HEXTRA` conversion options and no seeded ALLSONG title
  fixture.
- Generated `out_stock.lib` SHA-256:
  `ff518da115e2a748b72bc130a15825d5d2ad5b0e44d592cebb3c01782c695546`.

Equivalent invocation, replacing the two input paths with local copies of the
hash-pinned files:

```sh
docker run --rm \
  -v "$REPO:/repo:ro" \
  -v "$STOCK_FW:/stock:ro" \
  -v "$DEVICE_MUSIC_LIB:/input/MUSIC.LIB:ro" \
  -v "$WORK:/w" \
  -e CROSS=mipsel-linux-gnu- \
  -e QEMU=qemu-mipsel-static \
  m30-qemu-tools:debian-stable \
  bash /repo/tools/library/test.sh /stock/playlist.ap /input/MUSIC.LIB /w stock
```

`analysis/stock/fw` is a symlink to the stock firmware directory. For this
container run, its target was mounted directly at `/stock`.

## Captured harness output

```text
library: 4000 songs, keylen 16, tables from sector 2001
running the stock index passes
file ops: seeks 24896, reads 24101 (782320 bytes), writes 1 (512 bytes), appends 102 (267 sectors)
calls inside a sector: 24100, across a 4 KB block: 0  <-- the player's kernel gets these wrong
sector counter after the passes: 2268
MISMATCH: 1444454 bytes differ, first at 0x000fa282 (sector 2001)
  got      ca 0c de 0b fc 0e fe 0e da 07 19 09 97 02 83 0f
  expected de 0b ca 0c fc 0e fe 0e da 07 19 09 97 02 83 0f
counts: 0 0 0 549 0 0 363 707 0 30 439 709 0 0 0 0 0 0
nodes : 0 0 0 2033 0 0 2099 2111 0 2182 2183 2197 0 0 0 0 0 0
FAIL
```

## Captured section comparison

```text
songs: expected 4000, got 4000
header identical
ALLSONG: counts (0, 0, 0) sectors (0, 0, 0, 2001, 2017, 0)
   order table: 3963 of 4000 identical
      first diff at position 65: expected song 3038 '01. Dokunmayarayaparsın.flac' / '01. Dokunmayarayaparsın.flac', got song 3274 '01. Dokunmayarayaparsın.wav' / '01. Dokunmayarayaparsın.wav'
   inverse table: 3963 of 4000 identical
      first diff at song 22 "03. 'Til I Collapse Freestyle.": expected 686, got 685
ALBUM: counts (549, 0, 0) sectors (2033, 0, 0, 2051, 2067, 2083)
   level 0 nodes: 547 of 549 identical
      first diff at node 124: expected 010100000300eb0bffffffff7b007d00 (3 songs, first 3051 "Dünya'dan Kovuldu (In"), got 010100001000d905ffffffff7b007d00 (16 songs, first 1497 'Duality')
   order table: 3973 of 4000 identical
      first diff at position 663: expected song 1804 'Continuance' / '04. Obsession.flac', got song 1817 'Continuance' / '04. Obsession.flac'
   inverse table: 3973 of 4000 identical
      first diff at song 1497 'Duality': expected 862, got 859
   node index table: 3981 of 4000 identical
      first diff at song 1497 'Duality': expected 125, got 124
ARTIST: counts (363, 707, 0) sectors (2099, 2111, 0, 2134, 2150, 2166)
   level 0 nodes: 363 of 363 identical
   level 1 nodes: 707 of 707 identical
   order table: 3992 of 4000 identical
      first diff at position 1464: expected song 1804 'Curren$y &' / '04. Obsession.flac', got song 1817 'Curren$y &' / '04. Obsession.flac'
   inverse table: 3992 of 4000 identical
      first diff at song 1804 'Curren$y &': expected 1464, got 1465
   node index table: 4000 of 4000 identical
GENRE: counts (30, 439, 709) sectors (2182, 2183, 2197, 2220, 2236, 2252)
   level 0 nodes: 30 of 30 identical
   level 1 nodes: 439 of 439 identical
   level 2 nodes: 709 of 709 identical
   order table: 3992 of 4000 identical
      first diff at position 1256: expected song 1804 'Hip-Hop' / '04. Obsession.flac', got song 1817 'Hip-Hop' / '04. Obsession.flac'
   inverse table: 3992 of 4000 identical
      first diff at song 1804 'Hip-Hop': expected 1256, got 1257
   node index table: 4000 of 4000 identical
```

The section-comparison output above is verbatim. `libdiff.py` prints only the
first differing node and table entry per level/table. Its `counts` values come
from the expected file's section header; the actual emitted counts are shown
in the preceding harness capture.

## Interpretation limits

The harness rebuilds index data from the captured records; it does not recreate
the full original library byte-for-byte. Its module-6 conversion is a test stub,
not the resident converter. The reported 24,100 unaligned sector calls are
flagged by the harness as a known kernel trouble case. This run therefore
characterizes the stock MIPS index code under the harness's conversion and file
service semantics; it does not identify the device's text conversion or
filesystem behavior.

## Cluster-boundary quirk follow-up

The same seedless input was rerun with the harness file-service option
`HEXTRA=-DQUIRK=4`. It produced the same section summaries and an output file
byte-identical to the default run; both `out_stock.lib` files have SHA-256
`ff518da115e2a748b72bc130a15825d5d2ad5b0e44d592cebb3c01782c695546`.
The harness reported zero transfers crossing a 4 KiB boundary. Since the
modeled cluster size is 64 KiB, no transfer crossed a cluster boundary either,
so the `QUIRK=4` truncation branch was not exercised. This eliminates that
branch as a cause in this particular run; it does not test a fragmented card
layout or a device filesystem implementation.

Reproduction uses the command above with this additional environment setting:

```sh
-e HEXTRA=-DQUIRK=4
```
