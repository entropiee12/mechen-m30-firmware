#!/usr/bin/env python3
"""Check the recovered structural contract of the M30 key driver.

This probe deliberately checks only facts that are visible in the extracted
``key.drv`` bytes.  It does not execute the driver and does not infer which
physical button produces each normalized code.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "emu"))

from yqhx import YqhxImage  # noqa: E402


KEY_SEGMENT_FILE_OFFSET = 0x200
KEY_SEGMENT_LENGTH = 0xA00
KEY_SEGMENT_DESTINATION = 0xBFC10000
KEY_BSS_ADDRESS = 0x9FC14328
KEY_BSS_SIZE = 0xD6
KEY_ENTRY = 0x290094C4

# FUN_bfc10178 compares the sampled 7-bit key value against this table.  The
# final zero terminates the thresholds; the driver keeps the table in its
# resident segment at 0xbfc103c0 (file offset 0x5c0).
KEY_ADC_THRESHOLDS = (0x03, 0x18, 0x2A, 0x3A, 0x4A, 0x5F, 0x6F, 0x00)
KEY_THRESHOLD_FILE_OFFSET = 0x5C0


def verify(path: Path) -> None:
    image = YqhxImage.load(path)
    segment = image.segments[0]
    expected = (KEY_SEGMENT_FILE_OFFSET, KEY_SEGMENT_LENGTH, KEY_SEGMENT_DESTINATION)
    actual = (segment.file_offset, segment.length, segment.destination)
    if actual != expected:
        raise AssertionError(f"key segment {actual!r} != {expected!r}")
    if (image.bss_address, image.bss_size) != (KEY_BSS_ADDRESS, KEY_BSS_SIZE):
        raise AssertionError(
            f"key BSS {(image.bss_address, image.bss_size)!r} != "
            f"{(KEY_BSS_ADDRESS, KEY_BSS_SIZE)!r}"
        )
    if image.entry != KEY_ENTRY:
        raise AssertionError(f"key entry 0x{image.entry:08x} != 0x{KEY_ENTRY:08x}")

    data = path.read_bytes()
    thresholds = tuple(data[KEY_THRESHOLD_FILE_OFFSET : KEY_THRESHOLD_FILE_OFFSET + 8])
    if thresholds != KEY_ADC_THRESHOLDS:
        raise AssertionError(f"ADC thresholds {thresholds!r} != {KEY_ADC_THRESHOLDS!r}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("key_driver", type=Path)
    args = parser.parse_args()
    verify(args.key_driver)
    print(
        "KEY BINARY PROBE PASS "
        f"segment=0x{KEY_SEGMENT_DESTINATION:08x}+0x{KEY_SEGMENT_LENGTH:x} "
        f"thresholds={','.join(f'{value:#04x}' for value in KEY_ADC_THRESHOLDS[:-1])}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
