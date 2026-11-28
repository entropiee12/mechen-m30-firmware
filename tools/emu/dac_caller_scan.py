#!/usr/bin/env python3
"""Scan extracted firmware artifacts for direct DAC-driver references.

This is deliberately a negative-evidence tool.  A missing little-endian
pointer does not rule out an indirect loader or a fixed-ROM caller; it only
rules out that particular raw representation in the scanned files.
"""

from __future__ import annotations

import argparse
import struct
from pathlib import Path


DEFAULT_VALUES = (
    0xBFC18D00,
    0xBFC18D08,
    0xBFC18DB8,
    0xBFC18FA0,
    0xBFC19060,
    0xBFC19114,
    0xBFC191D4,
    0xBFC19240,
    0xBFC19330,
)


def parse_value(text: str) -> int:
    value = int(text, 0)
    if not 0 <= value <= 0xFFFFFFFF:
        raise argparse.ArgumentTypeError(f"not a 32-bit value: {text}")
    return value


def scan_file(path: Path, needles: dict[int, bytes]) -> list[tuple[int, int]]:
    data = path.read_bytes()
    hits: list[tuple[int, int]] = []
    for value, needle in needles.items():
        offset = 0
        while True:
            offset = data.find(needle, offset)
            if offset < 0:
                break
            hits.append((value, offset))
            offset += 1
    return sorted(hits)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path, help="directory containing extracted artifacts")
    parser.add_argument(
        "--value",
        dest="values",
        action="append",
        type=parse_value,
        help="additional/replacement 32-bit value; repeatable",
    )
    parser.add_argument(
        "--exclude-name",
        action="append",
        default=["dec.bin"],
        help="basename to skip (repeatable; default: dec.bin)",
    )
    parser.add_argument(
        "--include-name",
        action="append",
        default=[],
        help="basename to include even when it is listed by --exclude-name",
    )
    parser.add_argument(
        "--require-zero",
        action="store_true",
        help="return failure if any requested value is found",
    )
    args = parser.parse_args()

    values = tuple(args.values) if args.values else DEFAULT_VALUES
    needles = {value: struct.pack("<I", value) for value in values}
    excluded_names = set(args.exclude_name) - set(args.include_name)
    totals = {value: 0 for value in values}
    files = 0
    for path in sorted(p for p in args.root.rglob("*") if p.is_file()):
        if path.name in excluded_names:
            continue
        files += 1
        for value, offset in scan_file(path, needles):
            totals[value] += 1
            print(f"DIRECT_REF value=0x{value:08x} file={path} offset=0x{offset:x}")

    for value in values:
        print(f"DIRECT_TOTAL value=0x{value:08x} count={totals[value]}")
    print(f"SCANNED files={files} root={args.root}")
    if args.require_zero and any(totals.values()):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
