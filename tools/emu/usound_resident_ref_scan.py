#!/usr/bin/env python3
"""Scan .ap/.drv artifacts for raw references to a resident firmware address.

This finds literal byte sequences only. It cannot detect computed addresses,
indirect dispatch, or references in artifacts outside the supplied root.
"""

from __future__ import annotations

import argparse
import hashlib
import struct
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path, nargs="?", default=Path("."))
    parser.add_argument("--value", type=lambda value: int(value, 0), default=0xBFC166AD)
    args = parser.parse_args()

    if not 0 <= args.value <= 0xFFFFFFFF:
        parser.error("--value must be a 32-bit unsigned integer")

    needles: dict[bytes, str] = {}
    for order, needle in (
        ("LE", struct.pack("<I", args.value)),
        ("BE", struct.pack(">I", args.value)),
    ):
        needles[needle] = f"{needles[needle]}/{order}" if needle in needles else order
    files = sorted(
        path for path in args.root.rglob("*")
        if path.is_file() and path.suffix.lower() in {".ap", ".drv"}
    )
    hit_count = 0
    print(f"SCAN value=0x{args.value:08x} files={len(files)} root={args.root}")
    for path in files:
        data = path.read_bytes()
        digest: str | None = None
        for needle, order in needles.items():
            offset = 0
            while True:
                offset = data.find(needle, offset)
                if offset < 0:
                    break
                if digest is None:
                    digest = hashlib.sha256(data).hexdigest()
                print(
                    f"REF endian={order} file={path} offset=0x{offset:x} "
                    f"sha256={digest}"
                )
                hit_count += 1
                offset += 1

    print(f"TOTAL value=0x{args.value:08x} refs={hit_count} files={len(files)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
