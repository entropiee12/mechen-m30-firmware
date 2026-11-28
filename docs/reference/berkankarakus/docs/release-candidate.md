# M30 safe UI candidate recipe

There is currently no persistent flashable candidate in this worktree. The
reproducible candidate boundary is the extracted community member tree plus
the two verified resource changes below.

> **Correction (static analysis; not device tested):** The Previous-as-restart
> patch is excluded. The `0x3a64` value is a fast-backward offset, and the
> Previous handler has no elapsed-time threshold. Changing it does not establish
> restart behavior. See [`codex-handoff.md`](codex-handoff.md)
> and [`findings-artwork-start.md`](findings-artwork-start.md#3-previous-key).

## Source and scope

- Source: community V1.292.00, SHA-256
  `5e17d883f697a67d4face6f624ddc30952c5542c918b219be974bf380383ed2c`.
- Changed members: `music.sty` and `setting.sty` only.
- `mengine.ap` and `common.sty` remain byte-identical to the community
  baseline; the unsafe Previous experiment is excluded.
- No 24,000-track experimental library patch is included.
- Physical device acceptance has not been claimed for this candidate.

## Reproduce the extracted candidate

```sh
OUT_DIR="build/m30-safe-ui"
mkdir -p "$OUT_DIR"
PYTHONPATH=tools/emu python3 tools/emu/build_safe_ui_members.py \
  analysis/community/fw "$OUT_DIR/fw"
```

Then verify the exact delta:

```sh
PYTHONPATH=tools/emu python3 tools/emu/candidate_delta_probe.py \
  analysis/community/fw "$OUT_DIR/fw" \
  --changed music.sty --changed setting.sty \
  --manifest "$OUT_DIR/delta.json"
```

The optional JSON manifest records every extracted member's baseline/candidate
SHA-256 and size, names the rollback/reference tree, and explicitly marks the
result as `flashable_hex: false`. It is a comparison artifact, not a firmware
image.

The builder emits a PPM preview outside the member directory. It does not
repack a HEX. A repack step may be documented only after the source HEX,
decrypted AFI, container offsets, checksums, and full round-trip extraction
are all available and tested.

## Custom EQ + 50 ms experiment

The current experimental candidate is built from the pinned community
V1.292.00 HEX. It changes exactly `music.ap` and `browser.ap`:

- `music.ap`: redirect the Custom EQ producer's seven-byte source pointer to
  an in-bank curve `(6, 5, 0, -2, 0, 4, 3)`; change the directory marquee
  interval from 80 ms to 50 ms.
- `browser.ap`: change the same directory marquee interval from 80 ms to
  50 ms.
- `config.ap`, `config.bin`, and the saved-settings records are byte-identical
  to the community source. This does not prove that a device upgrade preserves
  settings or that the new EQ curve is audible.
- While this patched Custom producer is active, it sends the fixed curve even
  if the stored Custom EQ values are edited. Those stored values are not
  overwritten by the HEX and should be read again after restoring an unmodified
  firmware; the menu's displayed/editable value behavior is not fully verified.

The command below is a recipe, not a promise that a clean clone contains its
inputs. It can run only when the verified source HEX and extracted corpus are
available locally. Supply the source path explicitly; do not assume a path
from the original workstation:

```sh
python3 tools/build_community_custom_eq_50ms.py \
  "$M30_SOURCE_HEX" \
  analysis/community/dec.bin analysis/community/fw \
  build/MECHEN_M30-community-v1.292-custom-curve-50ms.HEX
```

The generated HEX is a local build output and is intentionally not stored in
Git. The previously generated HEX mentioned in earlier session notes lived in
a workstation-specific temporary directory; it is not a portable input. A
clean clone without the source HEX or `analysis/community/` corpus cannot
rebuild or independently verify that candidate from Git alone.

The builder pins source hashes, requires a byte-identical no-op repack, checks
the extracted-member delta, recomputes container checksums, then decrypts its
own result and compares every firmware member slot and checksum against the
candidate tree. The extracted EQ/marquee checks and Ghidra source-pointer and
Custom producer p-code probes pass. The device report says Custom and Off were
not audibly different, and a photo showed all seven Custom sliders at 0 dB.
The device's `music.ap` was not identified by hash or by reading file offset
`0x86f8`, so the report does not establish that this candidate was active.
The listening conditions and a controlled built-in-preset comparison were not
recorded. The candidate's audible effect and persistence through UI edits
remain unestablished. Do not treat it as a successful EQ change or tune another
curve until the device image and message-to-audio consumer path are identified.
The HEX itself was generated in a workstation-specific temporary directory
and is not present in Git.

## Now-playing-only member candidate

For the narrower now-playing resource boundary, use:

```sh
OUT_DIR="build/m30-now-playing-member"
mkdir -p "$OUT_DIR"
PYTHONPATH=tools/emu python3 tools/emu/build_now_playing_member.py \
  analysis/community/fw "$OUT_DIR/fw"
```

This candidate changes exactly one extracted member: `music.sty`. It contains
only the verified default-artwork and transport-icon resource edits. The host
metadata rasterizer, EQ spacing, status silhouettes, and footer preview are
not firmware patches and are intentionally excluded. The output is therefore
safe to inspect and compare, but it is still not a flashable HEX until the
complete FWU source and round-trip repacker are available.

## Not claimed

This recipe does not fix folder-name clipping, the red marquee artefact,
filename ordering, persistent play counters, asynchronous artwork decoding,
true Previous-as-restart behavior, or physical card-event indexing.
