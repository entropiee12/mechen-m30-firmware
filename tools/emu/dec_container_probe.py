#!/usr/bin/env python3
"""Verify that the decrypted AFI image only repeats extracted members."""

from __future__ import annotations

import argparse
import struct
from pathlib import Path


MEMBERS = (
    "kernel.drv",
    "ker_text.bin",
    "ker_init.bin",
    "ker_xsys.bin",
    "drv_ui.drv",
    "drv_dac.drv",
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path, help="corpus directory containing dec.bin and fw/")
    args = parser.parse_args()
    dec = (args.root / "dec.bin").read_bytes()
    fw = args.root / "fw"

    for name in MEMBERS:
        member = (fw / name).read_bytes()
        offsets = []
        start = 0
        while True:
            start = dec.find(member, start)
            if start < 0:
                break
            offsets.append(start)
            start += 1
        if len(offsets) != 1:
            raise SystemExit(f"unexpected {name} occurrences: {offsets}")
        print(f"MEMBER name={name} offset=0x{offsets[0]:x} size=0x{len(member):x}")

    pointer = struct.pack("<I", 0xBFC06D6C)
    hits = []
    start = 0
    while True:
        start = dec.find(pointer, start)
        if start < 0:
            break
        hits.append(start)
        start += 1
    if hits:
        raise SystemExit(f"fixed exception pointer unexpectedly present: {hits}")
    print(f"DEC CONTAINER PASS size=0x{len(dec):x} fixed-handler-pointer=absent")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
