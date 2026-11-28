#!/usr/bin/env python3
"""Verify that container pointer hits are copies of the UI-driver consumer.

The extracted AFI containers can contain the same firmware member in another
layout.  A pointer-presence scan cannot distinguish that from an independent
provider.  This probe compares a bounded byte window around each observed far
pointer with the corresponding ``fw/drv_ui.drv`` bytes and fails closed when a
window has no consumer match.
"""

from __future__ import annotations

import argparse
import json
import struct
from dataclasses import asdict, dataclass
from pathlib import Path

from provider_scan import OBSERVED_POINTERS


DEFAULT_CONTAINER_FILES = ("afi/FWIMAGE.FW", "dec.bin")


@dataclass(frozen=True)
class UnmatchedWindow:
    path: str
    offset: int
    pointer: int


@dataclass(frozen=True)
class ContextSummary:
    root: str
    occurrences: int
    unmatched: tuple[UnmatchedWindow, ...]


def _offsets(data: bytes, pointer: int) -> tuple[int, ...]:
    needle = struct.pack("<I", pointer)
    hits: list[int] = []
    start = 0
    while True:
        offset = data.find(needle, start)
        if offset < 0:
            return tuple(hits)
        hits.append(offset)
        start = offset + 1


def _window(data: bytes, offset: int, before: int, after: int) -> bytes:
    return data[max(0, offset - before) : min(len(data), offset + after)]


def verify_root(
    root: Path,
    *,
    container_files: tuple[str, ...] = DEFAULT_CONTAINER_FILES,
    before: int = 0x20,
    after: int = 0x40,
) -> ContextSummary:
    if before < 0 or after <= 0:
        raise ValueError("context bounds must be before >= 0 and after > 0")
    driver_path = root / "fw" / "drv_ui.drv"
    if not driver_path.is_file():
        raise FileNotFoundError(driver_path)
    driver = driver_path.read_bytes()
    unmatched: list[UnmatchedWindow] = []
    occurrences = 0
    for relative in container_files:
        path = root / relative
        if not path.is_file():
            continue
        data = path.read_bytes()
        for pointer in OBSERVED_POINTERS:
            for offset in _offsets(data, pointer):
                occurrences += 1
                context = _window(data, offset, before, after)
                if driver.find(context) < 0:
                    unmatched.append(
                        UnmatchedWindow(str(path), offset, pointer)
                    )
    return ContextSummary(str(root), occurrences, tuple(unmatched))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("roots", nargs="+", type=Path)
    parser.add_argument("--before", type=lambda value: int(value, 0), default=0x20)
    parser.add_argument("--after", type=lambda value: int(value, 0), default=0x40)
    parser.add_argument("--json", action="store_true", dest="as_json")
    args = parser.parse_args()

    summaries = tuple(
        verify_root(root, before=args.before, after=args.after)
        for root in args.roots
    )
    if args.as_json:
        print(json.dumps([asdict(summary) for summary in summaries], indent=2))
    else:
        for summary in summaries:
            print(
                f"PROVIDER CONTEXT {'PASS' if not summary.unmatched else 'FAIL'} "
                f"root={summary.root} occurrences={summary.occurrences} "
                f"unmatched={len(summary.unmatched)}"
            )
            for item in summary.unmatched:
                print(
                    f"  unmatched path={item.path} offset=0x{item.offset:x} "
                    f"pointer=0x{item.pointer:08x}"
                )
    return 1 if any(summary.unmatched for summary in summaries) else 0


if __name__ == "__main__":
    raise SystemExit(main())
