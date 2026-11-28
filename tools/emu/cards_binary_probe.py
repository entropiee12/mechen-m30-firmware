#!/usr/bin/env python3
"""Check the extracted SD-card controller driver's static boundary."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "emu"))

from yqhx import YqhxImage  # noqa: E402


CARDS_SEGMENT = (0x200, 0x1400, 0xBFC28000)
CARDS_BSS = (0x9FC19544, 0)
CARDS_ENTRY = 0xBFC28D60
CARDS_MARKERS = (b"rwclk", b"rwerr", b"reset sd:", b"ack:")


def verify(path: Path) -> None:
    image = YqhxImage.load(path)
    segment = image.segments[0]
    actual_segment = (segment.file_offset, segment.length, segment.destination)
    if actual_segment != CARDS_SEGMENT:
        raise AssertionError(f"SD-card segment {actual_segment!r} != {CARDS_SEGMENT!r}")
    if (image.bss_address, image.bss_size) != CARDS_BSS:
        raise AssertionError(
            f"SD-card BSS {(image.bss_address, image.bss_size)!r} != {CARDS_BSS!r}"
        )
    if image.entry != CARDS_ENTRY:
        raise AssertionError(f"SD-card entry 0x{image.entry:08x} != 0x{CARDS_ENTRY:08x}")

    data = path.read_bytes()
    missing = [marker.decode("ascii") for marker in CARDS_MARKERS if marker not in data]
    if missing:
        raise AssertionError(f"missing SD-card markers: {missing!r}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("cards_driver", type=Path)
    args = parser.parse_args()
    verify(args.cards_driver)
    print(
        "SD CARD BINARY PROBE PASS "
        "segment=0xbfc28000+0x1400 bss=0x9fc19544+0x0 "
        "markers=rwclk,rwerr,reset-sd,ack"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
