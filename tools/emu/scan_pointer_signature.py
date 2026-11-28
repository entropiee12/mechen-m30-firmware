#!/usr/bin/env python3
"""Scan extracted M30 binaries for a masked 32-bit pointer signature."""

from __future__ import annotations

import argparse
import struct
import sys
from dataclasses import dataclass
from pathlib import Path


MASK = 0xFF00FF00
VALUE = 0x73005A00
EXTENSIONS = {".ap", ".drv", ".bin"}


@dataclass(frozen=True)
class Match:
    path: str
    offset: int
    word: int


def scan_file(path: Path) -> tuple[Match, ...]:
    data = path.read_bytes()
    matches: list[Match] = []
    for offset in range(0, len(data) - 3):
        word = struct.unpack_from("<I", data, offset)[0]
        if word & MASK == VALUE:
            matches.append(Match(str(path), offset, word))
    return tuple(matches)


def scan_tree(root: Path) -> tuple[Match, ...]:
    matches: list[Match] = []
    for path in sorted(item for item in root.rglob("*") if item.is_file() and item.suffix.lower() in EXTENSIONS):
        matches.extend(scan_file(path))
    return tuple(matches)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("--require-zero", action="store_true")
    args = parser.parse_args()
    matches = scan_tree(args.root)
    print(f"mask=0x{MASK:08x} value=0x{VALUE:08x} matches={len(matches)}")
    for match in matches:
        print(f"  {match.path}:0x{match.offset:x} word=0x{match.word:08x}")
    if args.require_zero and matches:
        print("unexpected masked pointer signature", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
