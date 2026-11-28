# MUSIC.LIB terminal out-of-range-zero cross-check

This directory preserves the text outputs from the second-library stock
MIPS16 indexer run. The source library and playlist are external inputs and
are not copied into this repository.

## Inputs and run

- Public 251-record `MUSIC.LIB`, 1,536,000 bytes, SHA-256
  `cc3f476c8edb50ea8493b726d46ac82b139a6e87a86cc04c2cdbc30fd3fb8afe`.
- Stock `playlist.ap`, SHA-256
  `184f2c201537e6657024a2a5a054d12fe4a7f134e06205f0786a8d71d2a29403`.
- Docker image: `m30-qemu-tools:debian-stable`.
- Environment: `CROSS=mipsel-linux-gnu-`, `QEMU=qemu-mipsel-static`,
  `HEXTRA=-DMODE_REPLACE -DTERMINAL_OOR_ZERO -DFILL=1`.
- Entry point: `tools/library/test.sh`, mode `stock`.

The original local inputs were
`/Users/berkan/Projects/m30-work/community/fw/playlist.ap` and
`/Users/berkan/Projects/mechen-m30-evidence/MUSIC.LIB`. They are identified
here by hash so a later run can verify replacement inputs before use.

The generated `out_stock.lib` was 73,728 bytes with SHA-256
`a6d15d9b6fb9f8c3bade6f9961fd74cd902ef92e42c703514dae67288ae370d2`.
Its binary and the input files are not included; the preserved raw textual
outputs are `harness_stock.txt` and `libdiff_stock.txt`.
The harness output preserves two trailing spaces used to align its byte
columns. Its path-specific Git whitespace check is disabled so the archived
output remains byte-identical to the captured run.

## Interpretation

The detailed index comparison reports exact ALLSONG and ALBUM tables. ARTIST
and GENRE differ as described in
[`findings-music-lib-node-format.md`](../../findings-music-lib-node-format.md).
This is a cross-check against a public sample, not evidence that the file came
from a physical M30. It does not identify the firmware text converter or prove
that the candidate transform generalizes to other libraries.
