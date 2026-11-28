#!/usr/bin/env python3
"""Verify the raw library extension masks in ``playlist.ap``."""

from __future__ import annotations

import argparse
from pathlib import Path
import struct
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "sim"))

from filesystem_extension import M3U_LIBRARY_MASK, MUSIC_LIBRARY_MASK  # noqa: E402


MUSIC_MASK_OFFSET = 0x2A20
M3U_MASK_OFFSET = 0x2A28
EXPECTED_MASKS = (MUSIC_LIBRARY_MASK, M3U_LIBRARY_MASK)


def read_masks(path: Path) -> tuple[int, int]:
    data = path.read_bytes()
    if len(data) < M3U_MASK_OFFSET + 4:
        raise AssertionError(f"playlist member is shorter than the mask table: {path}")
    return (
        struct.unpack_from("<I", data, MUSIC_MASK_OFFSET)[0],
        struct.unpack_from("<I", data, M3U_MASK_OFFSET)[0],
    )


def verify(path: Path) -> tuple[int, int]:
    actual = read_masks(path)
    expected = tuple(mask.value for mask in EXPECTED_MASKS)
    if actual != expected:
        raise AssertionError(f"{path} masks {actual!r} != {expected!r}")
    return actual


def verify_corpus(root: Path) -> tuple[Path, ...]:
    paths = tuple(sorted(root.glob("*/fw/playlist.ap")))
    if not paths:
        raise AssertionError(f"no playlist.ap corpus members under {root}")
    for path in paths:
        verify(path)
    return paths


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("playlist_ap", type=Path)
    parser.add_argument("--corpus-root", type=Path, default=ROOT / "analysis")
    args = parser.parse_args()

    values = verify(args.playlist_ap)
    paths = verify_corpus(args.corpus_root)
    print(
        "LIBRARY MASK PASS "
        f"music_offset=0x{MUSIC_MASK_OFFSET:x} music=0x{values[0]:08x} "
        f"music_bits={MUSIC_LIBRARY_MASK.enabled_bit_positions} "
        f"music_slots={MUSIC_LIBRARY_MASK.driver_slot_positions} "
        f"m3u_offset=0x{M3U_MASK_OFFSET:x} m3u=0x{values[1]:08x} "
        f"m3u_bits={M3U_LIBRARY_MASK.enabled_bit_positions} "
        f"m3u_slots={M3U_LIBRARY_MASK.driver_slot_positions} "
        f"corpus={len(paths)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
