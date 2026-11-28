#!/usr/bin/env python3
"""Check the non-empty YQHX segments of the SD-card adapter driver."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "emu"))

from yqhx import YqhxImage  # noqa: E402


CARD_SEGMENTS = (
    (0x200, 0x9D0, 0xBFC17400),
    (0xC00, 0xA10, 0xBFC28000),
    (0x1700, 0x44, 0x9FC19500),
)
CARD_BSS = (0x9FC19544, 0)
CARD_ENTRY = 0xBFC28494
CARD_MARKERS = (b"rwclk", b"rwerr")


def verify(path: Path) -> None:
    image = YqhxImage.load(path)
    actual_segments = tuple(
        (segment.file_offset, segment.length, segment.destination)
        for segment in image.segments
        if segment.length
    )
    if actual_segments != CARD_SEGMENTS:
        raise AssertionError(f"SD adapter segments {actual_segments!r} != {CARD_SEGMENTS!r}")
    if (image.bss_address, image.bss_size) != CARD_BSS:
        raise AssertionError(
            f"SD adapter BSS {(image.bss_address, image.bss_size)!r} != {CARD_BSS!r}"
        )
    if image.entry != CARD_ENTRY:
        raise AssertionError(f"SD adapter entry 0x{image.entry:08x} != 0x{CARD_ENTRY:08x}")

    data = path.read_bytes()
    missing = [marker.decode("ascii") for marker in CARD_MARKERS if marker not in data]
    if missing:
        raise AssertionError(f"missing SD adapter markers: {missing!r}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("card_driver", type=Path)
    args = parser.parse_args()
    verify(args.card_driver)
    print(
        "SD ADAPTER BINARY PROBE PASS "
        "segments=3 bfc17400+0x9d0,bfc28000+0xa10,9fc19500+0x44 "
        "entry=0xbfc28494 markers=rwclk,rwerr"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
