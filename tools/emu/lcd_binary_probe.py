#!/usr/bin/env python3
"""Check the recovered structural contract of ``drv_lcd.drv``."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "emu"))

from yqhx import YqhxImage  # noqa: E402


LCD_SEGMENT = (0x200, 0x800, 0xBFC0A000)
LCD_BSS = (0x9FC14234, 0xC8)
LCD_ENTRY = 0x17009000
LCD_PAYLOAD_PREFIX = bytes.fromhex("91643d67446748c1")


def verify(path: Path) -> None:
    image = YqhxImage.load(path)
    segment = image.segments[0]
    actual_segment = (segment.file_offset, segment.length, segment.destination)
    if actual_segment != LCD_SEGMENT:
        raise AssertionError(f"LCD segment {actual_segment!r} != {LCD_SEGMENT!r}")
    if (image.bss_address, image.bss_size) != LCD_BSS:
        raise AssertionError(
            f"LCD BSS {(image.bss_address, image.bss_size)!r} != {LCD_BSS!r}"
        )
    if image.entry != LCD_ENTRY:
        raise AssertionError(f"LCD entry 0x{image.entry:08x} != 0x{LCD_ENTRY:08x}")
    payload = path.read_bytes()[LCD_SEGMENT[0] : LCD_SEGMENT[0] + len(LCD_PAYLOAD_PREFIX)]
    if payload != LCD_PAYLOAD_PREFIX:
        raise AssertionError(f"LCD payload prefix {payload.hex()} != {LCD_PAYLOAD_PREFIX.hex()}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("lcd_driver", type=Path)
    args = parser.parse_args()
    verify(args.lcd_driver)
    print(
        "LCD BINARY PROBE PASS "
        f"segment=0x{LCD_SEGMENT[2]:08x}+0x{LCD_SEGMENT[1]:x} "
        "bss=0x9fc14234+0xc8"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
