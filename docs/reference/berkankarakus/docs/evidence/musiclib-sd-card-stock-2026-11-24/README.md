# SD-card MUSIC.LIB stock-indexer cross-check

This read-only run compared the stock MIPS index passes against the
`MUSIC.LIB` on the user's mounted SD card. The card file was mounted into
Docker with `:ro`; the run wrote its generated files only to a workspace
directory.

## Inputs and run

- SD-card `MUSIC.LIB`: 2,625,536 bytes, SHA-256
  `02c10ceb6463d60201e531757350999d52d8bf6d66614a2183f13642fca8d017`.
- Stock `playlist.ap`: SHA-256
  `7b1971f1496318a9682b0abc7681c058ded702aa533c97a32e428f5088b77b64`.
- Docker image: `m30-qemu-tools:debian-stable`.
- Mode: `stock`; `HEXTRA=-DMODE_REPLACE -DTERMINAL_OOR_ZERO -DFILL=1`.
- Entry point: `tools/library/test.sh`; QEMU CPU limit 1, memory limit 1 GiB.

Equivalent invocation, substituting local paths for the read-only mounts:

```sh
docker run --rm --cpus=1 --memory=1g --pids-limit=64 \
  -v "$REPO/tools/library:/tl:ro" \
  -v "$SD_CARD:/card:ro" \
  -v "$STOCK_FW/playlist.ap:/input/playlist.ap:ro" \
  -v "$WORK:/w" \
  -e CROSS=mipsel-linux-gnu- \
  -e QEMU=qemu-mipsel-static \
  -e 'HEXTRA=-DMODE_REPLACE -DTERMINAL_OOR_ZERO -DFILL=1' \
  m30-qemu-tools:debian-stable \
  bash /tl/test.sh /input/playlist.ap /card/MUSIC.LIB /w stock
```

The captured harness and section-comparison output are preserved verbatim in
[`harness_stock.txt`](harness_stock.txt) and
[`libdiff_stock.txt`](libdiff_stock.txt). Generated output was 1,160,704 bytes
with SHA-256
`8b66d44e824645df57c39e8caa70593631e78f726b4702b03f49d3456243d06f`.
The generated binary and input library are not copied into the repository.

## Result and limits

All four sections' order tables and inverse tables matched all 4,000 entries.
ALBUM's 549 level-0 nodes and node-index table matched; ARTIST's 359 level-0
and 703 level-1 nodes and node-index table matched; GENRE's 31 level-0, 436
level-1, and 706 level-2 nodes and node-index table matched. The output counts
also match the card library's section counts.

The harness reports `MISMATCH` at offset `0x11b600`, exactly the end of its
1,160,704-byte generated file. It generated the index region, not the library's
remaining records and trailing sectors, so this is an output-length boundary,
not a mismatch in the compared index tables.

This is an independent 4,000-record library cross-check, distinct from the
previously studied `e386db27...` image. It supports this stock-indexer run
under the harness's `TERMINAL_OOR_ZERO` conversion stub. It does not prove the
resident converter uses that rule, reproduce the whole library, or establish
that every library behaves the same way. The harness also reports 24,102
unaligned in-sector calls as a known kernel-trouble case.
