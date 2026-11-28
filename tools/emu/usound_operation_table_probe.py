#!/usr/bin/env python3
"""Verify the eight-word operation-table candidate in usound.drv data."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import struct
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "emu"))

from yqhx import YqhxImage  # noqa: E402


EXPECTED_DATA_SEGMENT = (0x2900, 0x154, 0x9FC33C00)
EXPECTED_SHA256 = "e593bdd29c2a96d4d745cf1b5cae4da2ee71c2211c551e415209b18f54e600fc"
EXPECTED_OPERATION_TABLE = (
    0xBFC3154D,
    0xBFC315F9,
    0xBFC31615,
    0xBFC31629,
    0xBFC31655,
    0xBFC31741,
    0xBFC31755,
    0xBFC318B9,
)


def verify(path: Path) -> tuple[int, ...]:
    data = path.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    if digest != EXPECTED_SHA256:
        raise AssertionError(f"usound.drv SHA256 {digest} != {EXPECTED_SHA256}")

    image = YqhxImage.load(path)
    segment = image.segments[3]
    actual_segment = (segment.file_offset, segment.length, segment.destination)
    if actual_segment != EXPECTED_DATA_SEGMENT:
        raise AssertionError(
            f"usound fourth segment {actual_segment!r} != {EXPECTED_DATA_SEGMENT!r}"
        )

    table_offset = segment.file_offset + segment.length - len(EXPECTED_OPERATION_TABLE) * 4
    operation_table = struct.unpack_from(
        f"<{len(EXPECTED_OPERATION_TABLE)}I",
        data,
        table_offset,
    )
    if operation_table != EXPECTED_OPERATION_TABLE:
        raise AssertionError(
            f"operation-table candidate {operation_table!r} != {EXPECTED_OPERATION_TABLE!r}"
        )

    return operation_table


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("usound_driver", type=Path)
    args = parser.parse_args()
    operation_table = verify(args.usound_driver)
    print(
        "USOUND OPERATION TABLE PASS "
        f"entries={len(operation_table)} op3=0x{operation_table[3]:08x} "
        "dispatch=table[op]-inference"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
