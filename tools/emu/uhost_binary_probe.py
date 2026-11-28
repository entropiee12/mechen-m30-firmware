#!/usr/bin/env python3
"""Check the extracted USB-host driver's structural and protocol boundary."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "emu"))

from yqhx import YqhxImage  # noqa: E402


UHOST_SEGMENT = (0x200, 0x798, 0xBFC17400)
UHOST_BSS = (0x9FC19528, 0x50)
UHOST_ENTRY = 0x12009000
CBW_SIGNATURE = b"USBC"  # USB Bulk-Only Transport command-block wrapper.


def verify(path: Path) -> None:
    image = YqhxImage.load(path)
    segment = image.segments[0]
    actual_segment = (segment.file_offset, segment.length, segment.destination)
    if actual_segment != UHOST_SEGMENT:
        raise AssertionError(f"USB-host segment {actual_segment!r} != {UHOST_SEGMENT!r}")
    if (image.bss_address, image.bss_size) != UHOST_BSS:
        raise AssertionError(
            f"USB-host BSS {(image.bss_address, image.bss_size)!r} != {UHOST_BSS!r}"
        )
    if image.entry != UHOST_ENTRY:
        raise AssertionError(f"USB-host entry 0x{image.entry:08x} != 0x{UHOST_ENTRY:08x}")

    data = path.read_bytes()
    count = data.count(CBW_SIGNATURE)
    if count != 6:
        raise AssertionError(f"USB-host CBW signatures {count} != 6")
    if b"USBS" in data:
        raise AssertionError("unexpected USB CSW signature in USB-host member")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("usb_host_driver", type=Path)
    args = parser.parse_args()
    verify(args.usb_host_driver)
    print(
        "USB HOST BINARY PROBE PASS "
        "segment=0xbfc17400+0x798 bss=0x9fc19528+0x50 cbw=6 csw=absent"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
