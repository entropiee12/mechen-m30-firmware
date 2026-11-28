#!/usr/bin/env python3
"""Build a Malta BIOS with the real M30 xsys selector and RAM-redirection shims."""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path


BIOS_SIZE = 4 * 1024 * 1024
SWAP_SIZE = 0x3E0000
XSYS_BIOS_OFFSET = 0x34000
XSYS_ENTRY_OFFSET = 0xA4
TABLE_OFFSET = 0x19974
TABLE_LIMIT_0 = 0x12000
TABLE_LIMIT_1 = 0x10101
BOUNDARY_OFFSET = 0x5F90
ENTRY_BOUNDARY_OFFSET = 0x6D6C
HARNESS_LIMIT = min(TABLE_OFFSET, BOUNDARY_OFFSET, ENTRY_BOUNDARY_OFFSET, XSYS_BIOS_OFFSET)
EXPECTED_XSYS_SHA256 = "deaf67edce7eca6397abd1ca91f742ccbe70f451bb4926c75c7e46862e3b2988"

PATCHES = (
    (0x0A4, 0x3C1A9FC2, 0x3C1A8002, "stack-index base"),
    (0x0E8, 0x3C1A9FC2, 0x3C1A8002, "stack-counter base"),
    (0x0FC, 0x3C1BBFC2, 0x3C1B8002, "ROM counter read base"),
    (0x108, 0x3C01BFC2, 0x3C018002, "ROM counter write base"),
    (0x110, 0x3C1A9FC2, 0x3C1A8002, "stack-top read base"),
    (0x11C, 0x3C019FC2, 0x3C018002, "stack-top write base"),
)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("ker_xsys", type=Path)
    parser.add_argument("harness", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    output_path = args.output.resolve()
    inputs = (args.ker_xsys.resolve(), args.harness.resolve())
    if output_path in inputs or (output_path.exists() and any(
        path.exists() and os.path.samefile(output_path, path) for path in inputs
    )):
        raise SystemExit("output must not overwrite ker_xsys.bin or the harness")

    xsys = bytearray(args.ker_xsys.read_bytes())
    digest = hashlib.sha256(xsys).hexdigest()
    if digest != EXPECTED_XSYS_SHA256:
        raise SystemExit(f"ker_xsys.bin SHA-256 mismatch: {digest}")
    if len(xsys) != 0x800:
        raise SystemExit(f"unexpected ker_xsys.bin size: 0x{len(xsys):x}")

    for offset, expected, replacement, label in PATCHES:
        actual = int.from_bytes(xsys[offset : offset + 4], "little")
        if actual != expected:
            raise SystemExit(
                f"unexpected {label} instruction at +0x{offset:x}: 0x{actual:08x}"
            )
        xsys[offset : offset + 4] = replacement.to_bytes(4, "little")

    image = bytearray(b"\xff" * BIOS_SIZE)
    harness = args.harness.read_bytes()
    if not harness or len(harness) > HARNESS_LIMIT:
        raise SystemExit(
            "harness must be non-empty and fit before reserved BIOS data "
            f"(maximum size 0x{HARNESS_LIMIT:x})"
        )
    image[: len(harness)] = harness
    image[XSYS_BIOS_OFFSET : XSYS_BIOS_OFFSET + len(xsys)] = xsys
    image[TABLE_OFFSET : TABLE_OFFSET + 4] = TABLE_LIMIT_0.to_bytes(4, "little")
    image[TABLE_OFFSET + 4 : TABLE_OFFSET + 8] = TABLE_LIMIT_1.to_bytes(4, "little")
    image[BOUNDARY_OFFSET : BOUNDARY_OFFSET + 8] = bytes.fromhex(
        "08 00 e0 03 00 00 00 00"  # jr ra; nop
    )
    image[ENTRY_BOUNDARY_OFFSET : ENTRY_BOUNDARY_OFFSET + 8] = bytes.fromhex(
        "08 00 e0 03 00 00 00 00"  # jr ra; nop
    )

    # Malta's little-endian loader swaps complete BIOS words before mapping ROM.
    for offset in range(0, SWAP_SIZE, 4):
        image[offset : offset + 4] = image[offset : offset + 4][::-1]
    output_path.write_bytes(image)
    print(
        "MALTA XSYS BIOS PASS "
        f"entry=0xbfc340{XSYS_ENTRY_OFFSET:02x} "
        f"table[0]=0x{TABLE_LIMIT_0:x} table[1]=0x{TABLE_LIMIT_1:x} "
        f"patched-ram-operands={len(PATCHES)} "
        f"stubs=0xbfc05f90,0xbfc06d6c xsys-sha256={digest}"
    )


if __name__ == "__main__":
    main()
