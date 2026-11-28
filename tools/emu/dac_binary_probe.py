#!/usr/bin/env python3
"""Check the recovered structural contract of ``drv_dac.drv``."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "emu"))

from yqhx import YqhxImage  # noqa: E402


DAC_SEGMENT = (0x200, 0x800, 0xBFC18D00)
DAC_BSS = (0x9FC16090, 0x16C)
DAC_ENTRY = 0xBFC18D08
DAC_PAYLOAD_PREFIX = bytes.fromhex("0f6b05e8106b05e8")


def verify(path: Path) -> None:
    image = YqhxImage.load(path)
    segment = image.segments[0]
    actual_segment = (segment.file_offset, segment.length, segment.destination)
    if actual_segment != DAC_SEGMENT:
        raise AssertionError(f"DAC segment {actual_segment!r} != {DAC_SEGMENT!r}")
    if (image.bss_address, image.bss_size) != DAC_BSS:
        raise AssertionError(
            f"DAC BSS {(image.bss_address, image.bss_size)!r} != {DAC_BSS!r}"
        )
    if image.entry != DAC_ENTRY:
        raise AssertionError(f"DAC entry 0x{image.entry:08x} != 0x{DAC_ENTRY:08x}")
    payload = path.read_bytes()[DAC_SEGMENT[0] : DAC_SEGMENT[0] + len(DAC_PAYLOAD_PREFIX)]
    if payload != DAC_PAYLOAD_PREFIX:
        raise AssertionError(f"DAC payload prefix {payload.hex()} != {DAC_PAYLOAD_PREFIX.hex()}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("dac_driver", type=Path)
    args = parser.parse_args()
    verify(args.dac_driver)
    print(
        "DAC BINARY PROBE PASS "
        f"segment=0x{DAC_SEGMENT[2]:08x}+0x{DAC_SEGMENT[1]:x} "
        "bss=0x9fc16090+0x16c"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
