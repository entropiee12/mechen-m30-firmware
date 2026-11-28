# HEX artifact policy

Flashable HEX files are generated artifacts, not repository sources. The
current worktree intentionally contains no persistent candidate under
`build/`; historical `/tmp` paths and superseded candidate hashes are not
treated as current inputs.

## Current sources

| Artifact | SHA-256 | Status |
|---|---|---|
| Stock `MECHEN_M30.HEX` | `6c7e27c8c90c4942664d66a0d6c9f9e48f8d3bc7d2d24223568255e233551258` | rollback/reference source |
| Community V1.292.00 `MECHEN_M30.HEX` | `5e17d883f697a67d4face6f624ddc30952c5542c918b219be974bf380383ed2c` | accepted baseline |

## Safe UI member candidate

`tools/emu/build_safe_ui_members.py` creates an extracted-member candidate
from `analysis/community/fw`. It changes exactly `music.sty` and
`setting.sty`, preserves their sizes, and verifies the exact member delta.
The command does not repack or create a flashable HEX.

Any future HEX artifact must record its full SHA-256, source baseline, changed
members, size/checksum result, and physical-test status in the same change
that introduces it. Do not copy stale paths from old local inventories into
active documentation.
