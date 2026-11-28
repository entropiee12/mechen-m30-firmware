#!/usr/bin/env python3
"""Pin the community manager AP used by the usound 0x201 route probe."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import struct


MANAGER_SHA256 = "d94d2406d5e88efee1ffacff2d399ce7d865410ac7458efdb9f185eae359af7a"
DISPATCHER = 0x7F196200


def verify(path: Path) -> None:
    data = path.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    if digest != MANAGER_SHA256:
        raise AssertionError(
            f"unexpected manager.ap SHA-256 {digest}; expected community v1.292"
        )

    mapped = False
    for offset in range(0x100, 0x1000, 12):
        file_offset, address, size = struct.unpack_from("<III", data, offset)
        if (
            file_offset
            and address
            and size
            and size <= 0x10000
            and file_offset + size <= len(data)
            and address <= DISPATCHER < address + size
        ):
            mapped = True
            break
    if not mapped:
        raise AssertionError(f"manager dispatcher 0x{DISPATCHER:x} is not in a valid AP bank")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("manager_ap", type=Path)
    args = parser.parse_args()
    verify(args.manager_ap)
    print("USOUND MANAGER AP BINARY PROBE PASS community-v1.292 dispatcher=0x7f196200")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
