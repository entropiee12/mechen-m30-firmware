#!/usr/bin/env python3
"""Check the extracted NOR user-data dispatch table."""

from __future__ import annotations

import argparse
from pathlib import Path
import struct
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "emu"))

from yqhx import YqhxImage  # noqa: E402


NORUD_SEGMENT = (0x200, 0x80, 0xBFC08F80)
NORUD_BSS = (0xBFC18D00, 0)
NORUD_ENTRY = 0x100091A8
NORUD_WORDS = (
    0x00000000,
    0x00000000,
    0x200498B5,
    0x10009001,
    0x1000900D,
    0x10009011,
    0x10009015,
    0x10009179,
    0x200497C5,
    0x20049801,
    0x00000000,
    0x00000000,
    0x100091A5,
)


def verify(path: Path) -> None:
    image = YqhxImage.load(path)
    segment = image.segments[0]
    actual_segment = (segment.file_offset, segment.length, segment.destination)
    if actual_segment != NORUD_SEGMENT:
        raise AssertionError(f"NOR user-data segment {actual_segment!r} != {NORUD_SEGMENT!r}")
    if (image.bss_address, image.bss_size) != NORUD_BSS:
        raise AssertionError(
            f"NOR user-data BSS {(image.bss_address, image.bss_size)!r} != {NORUD_BSS!r}"
        )
    if image.entry != NORUD_ENTRY:
        raise AssertionError(f"NOR user-data entry 0x{image.entry:08x} != 0x{NORUD_ENTRY:08x}")

    data = path.read_bytes()
    words = struct.unpack_from("<13I", data, NORUD_SEGMENT[0])
    if words != NORUD_WORDS:
        raise AssertionError(f"NOR user-data table {words!r} != {NORUD_WORDS!r}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("nor_user_data_driver", type=Path)
    args = parser.parse_args()
    verify(args.nor_user_data_driver)
    print(
        "NOR USER-DATA BINARY PROBE PASS "
        "segment=0xbfc08f80+0x80 code=external-dispatch-table pointers=10"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
