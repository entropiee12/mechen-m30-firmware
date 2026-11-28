#!/usr/bin/env python3
"""Check the extracted ``aud_dev.drv`` load segments and raw far table."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "emu"))

from yqhx import YqhxImage  # noqa: E402


AUDDEV_SEGMENT = (0x300, 0x8, 0xBFC09F58)
AUDDEV_TABLE_SEGMENT = (0x200, 0x54, 0x9FC09F00)
AUDDEV_BSS = (0x9FC34160, 0)
AUDDEV_ENTRY = 0x1C009000
AUDDEV_BYTES = bytes.fromhex("106b05e80f6b05e8")
AUDDEV_RAW_TABLE_OFFSET = 0x200
AUDDEV_RAW_TABLE_SIZE = 0x54
AUDDEV_OP18_VALUE = 0x2C149401


def verify(path: Path) -> None:
    image = YqhxImage.load(path)
    actual_segments = tuple(
        (segment.file_offset, segment.length, segment.destination)
        for segment in image.segments
        if segment.length
    )
    expected_segments = (AUDDEV_SEGMENT, AUDDEV_TABLE_SEGMENT)
    if actual_segments != expected_segments:
        raise AssertionError(
            f"audio-device segments {actual_segments!r} != {expected_segments!r}"
        )
    if (image.bss_address, image.bss_size) != AUDDEV_BSS:
        raise AssertionError(
            f"audio-device BSS {(image.bss_address, image.bss_size)!r} != {AUDDEV_BSS!r}"
        )
    if image.entry != AUDDEV_ENTRY:
        raise AssertionError(
            f"audio-device entry 0x{image.entry:08x} != 0x{AUDDEV_ENTRY:08x}"
        )
    body = path.read_bytes()[AUDDEV_SEGMENT[0] : AUDDEV_SEGMENT[0] + len(AUDDEV_BYTES)]
    if body != AUDDEV_BYTES:
        raise AssertionError(f"audio-device body {body.hex()} != {AUDDEV_BYTES.hex()}")

    data = path.read_bytes()
    table_end = AUDDEV_RAW_TABLE_OFFSET + AUDDEV_RAW_TABLE_SIZE
    if len(data) < table_end:
        raise AssertionError("audio-device raw table is truncated")

    op18_offset = AUDDEV_RAW_TABLE_OFFSET + 18 * 4
    op18_value = int.from_bytes(data[op18_offset : op18_offset + 4], "little")
    if op18_value != AUDDEV_OP18_VALUE:
        raise AssertionError(
            f"raw op-18 word 0x{op18_value:08x} != 0x{AUDDEV_OP18_VALUE:08x}"
        )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("audio_device_driver", type=Path)
    args = parser.parse_args()
    verify(args.audio_device_driver)
    print(
        "AUDIO DEVICE BINARY PROBE PASS "
        "segments=2 resident=0xbfc09f58+0x8 table=0x9fc09f00+0x54 "
        "table-entry18=0x2c149401"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
