#!/usr/bin/env python3
"""Verify the usound MMIO word-port literal pool."""

from __future__ import annotations

import argparse
import struct
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "sim"))
sys.path.insert(0, str(ROOT / "tools" / "emu"))

from usound_fifo import (  # noqa: E402
    ALTERNATE_PORT_ADDRESS,
    DATA_PORT_ADDRESS,
    TRANSFER_GATE_ADDRESS,
)
from yqhx import YqhxImage  # noqa: E402


CODE_ADDRESS = 0xBFC3194C
EXPECTED = (TRANSFER_GATE_ADDRESS, DATA_PORT_ADDRESS, ALTERNATE_PORT_ADDRESS)


def verify(path: Path) -> None:
    image = YqhxImage.load(path)
    segment = image.segments[0]
    if (segment.file_offset, segment.destination) != (0x200, 0xBFC30000):
        raise AssertionError("unexpected usound code segment mapping")
    data = path.read_bytes()
    offset = segment.file_offset + (CODE_ADDRESS - segment.destination)
    actual = struct.unpack_from("<3I", data, offset)
    if actual != EXPECTED:
        raise AssertionError(f"word-port pool {actual!r} != {EXPECTED!r}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("usound_driver", type=Path)
    args = parser.parse_args()
    verify(args.usound_driver)
    print(
        "USOUND FIFO BINARY PASS "
        "gate=0x9fc33e12 data-port=0xc0080084 alt-port=0xc0080088"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
