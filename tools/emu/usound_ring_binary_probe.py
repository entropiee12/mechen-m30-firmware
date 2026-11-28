#!/usr/bin/env python3
"""Verify the usound driver's recovered circular-buffer state pointers."""

from __future__ import annotations

import argparse
import struct
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "sim"))
sys.path.insert(0, str(ROOT / "tools" / "emu"))

from usb_ring import (  # noqa: E402
    AVAILABLE_BYTES_ADDRESS,
    COPY_CALLBACK_ADDRESS,
    READ_POINTER_ADDRESS,
    RESET_SIGNAL_ADDRESS,
    RING_ADDRESS,
    SHORT_READ_COUNTER_ADDRESS,
)
from yqhx import YqhxImage  # noqa: E402


CODE_ADDRESS = 0xBFC31728
EXPECTED_POINTERS = (
    AVAILABLE_BYTES_ADDRESS,
    SHORT_READ_COUNTER_ADDRESS,
    READ_POINTER_ADDRESS,
    RING_ADDRESS,
    COPY_CALLBACK_ADDRESS,
    RESET_SIGNAL_ADDRESS,
)


def verify(path: Path) -> None:
    image = YqhxImage.load(path)
    segment = image.segments[0]
    if (segment.file_offset, segment.destination) != (0x200, 0xBFC30000):
        raise AssertionError("unexpected usound code segment mapping")
    data = path.read_bytes()
    offset = segment.file_offset + (CODE_ADDRESS - segment.destination)
    actual = struct.unpack_from("<6I", data, offset)
    if actual != EXPECTED_POINTERS:
        raise AssertionError(f"ring pointer pool {actual!r} != {EXPECTED_POINTERS!r}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("usound_driver", type=Path)
    args = parser.parse_args()
    verify(args.usound_driver)
    print(
        "USOUND RING BINARY PASS "
        "ring=0x9fc34000 read=0x9fc33dec available=0x9fc33df0 "
        "short=0x9fc33df4 reset=0x9fc33e13 callback=0xbfc326a9"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
