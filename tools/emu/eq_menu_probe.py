#!/usr/bin/env python3
"""Verify the extracted M30 EQ callback table and menu references.

This is a byte-level boundary check.  It does not infer labels, language, or
runtime menu selection from the MCG format; it only verifies the callback
pointer topology observed in the four local firmware corpora.
"""

from __future__ import annotations

import argparse
from pathlib import Path


PRESET_POINTERS = (
    0x403D4CED,
    0x403D4D19,
    0x403D4D45,
    0x403D4D71,
    0x403D4D9D,
    0x403D4DC9,
    0x403D4DF5,
)
CUSTOM_POINTER = 0x403D4ED1
AP_TABLE_START = 0xDEEC
MCG_GROUP_STARTS = (0x2FEA, 0x3554, 0x3B90)
MCG_GROUP_STEP = 0x2A


def hits(data: bytes, value: int) -> list[int]:
    needle = value.to_bytes(4, "little")
    result: list[int] = []
    start = 0
    while True:
        offset = data.find(needle, start)
        if offset < 0:
            return result
        result.append(offset)
        start = offset + 1


def verify(ap_path: Path, mcg_path: Path) -> None:
    ap = ap_path.read_bytes()
    mcg = mcg_path.read_bytes()

    for index, pointer in enumerate(PRESET_POINTERS):
        ap_offsets = hits(ap, pointer)
        expected_ap = AP_TABLE_START + index * 0x14
        if ap_offsets != [expected_ap]:
            raise AssertionError(
                f"preset {index} AP refs {ap_offsets!r}, expected [{expected_ap:#x}]"
            )

        mcg_offsets = hits(mcg, pointer)
        expected_mcg = [start + index * MCG_GROUP_STEP for start in MCG_GROUP_STARTS]
        if mcg_offsets != expected_mcg:
            raise AssertionError(
                f"preset {index} MCG refs {mcg_offsets!r}, expected {expected_mcg!r}"
            )

    if hits(ap, CUSTOM_POINTER) or hits(mcg, CUSTOM_POINTER):
        raise AssertionError("Custom callback unexpectedly appears as a direct pointer")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("music_ap", type=Path)
    parser.add_argument("music_mcg", type=Path)
    args = parser.parse_args()
    verify(args.music_ap, args.music_mcg)
    print(
        "EQ MENU PROBE PASS presets=7 ap_table=0xdeec..0xdf64 "
        "mcg_groups=3 custom_direct=none"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
