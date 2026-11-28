#!/usr/bin/env python3
"""Build a Malta BIOS image around the extracted M30 exception vector.

QEMU's Malta MIPS-LE path byte-swaps each 32-bit word in a supplied BIOS
image before creating the read-only reset-address mirror. This builder emits
the inverse word order so guest-visible instructions and data stay little
endian after QEMU's swap.
"""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path


BIOS_SIZE = 4 * 1024 * 1024
SWAP_SIZE = 0x3E0000
VECTOR_BIOS_OFFSET = 0x8180
VECTOR_DRIVER_OFFSET = 0x380
TABLE_SLOT_9_OFFSET = 0x1A9C8
TABLE_HANDLER = 0xBFC06F18
XSYS_BIOS_OFFSET = 0x34000
XSYS_SIZE = 0x800
XSYS_ENTRY = 0xBFC34074
HANDLER_BRIDGE_OFFSET = 0x6F18
EXPECTED_KERNEL_SHA256 = "58f9d0c62bdbdab7cfeee1c5e65bbe8b88ee962f8eecd3dd0515d1a1056d8bd6"
EXPECTED_XSYS_SHA256 = "deaf67edce7eca6397abd1ca91f742ccbe70f451bb4926c75c7e46862e3b2988"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("kernel_driver", type=Path)
    parser.add_argument("harness", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--xsys", type=Path,
                        help="install hash-pinned ker_xsys and bridge slot 9 to 0xbfc34074")
    args = parser.parse_args()

    output_path = args.output.resolve()
    sources = (args.kernel_driver, args.harness) + ((args.xsys,) if args.xsys else ())
    input_paths = {source.resolve() for source in sources}
    aliases_input = output_path in input_paths
    if output_path.exists():
        aliases_input |= any(
            source.exists() and os.path.samefile(output_path, source)
            for source in sources
        )
    if aliases_input:
        raise SystemExit("output path must not overwrite kernel.drv or harness")

    kernel = args.kernel_driver.read_bytes()
    digest = hashlib.sha256(kernel).hexdigest()
    if digest != EXPECTED_KERNEL_SHA256:
        raise SystemExit(f"kernel.drv SHA-256 mismatch: {digest}")
    if len(kernel) < VECTOR_DRIVER_OFFSET + 0x80:
        raise SystemExit("kernel.drv is truncated before exception vector")

    xsys = None
    xsys_digest = None
    if args.xsys:
        xsys = args.xsys.read_bytes()
        xsys_digest = hashlib.sha256(xsys).hexdigest()
        if xsys_digest != EXPECTED_XSYS_SHA256:
            raise SystemExit(f"ker_xsys.bin SHA-256 mismatch: {xsys_digest}")
        if len(xsys) != XSYS_SIZE:
            raise SystemExit(f"unexpected ker_xsys.bin size: 0x{len(xsys):x}")
        if XSYS_BIOS_OFFSET + len(xsys) > BIOS_SIZE:
            raise SystemExit("ker_xsys.bin does not fit in the Malta BIOS window")

    image = bytearray(b"\xff" * BIOS_SIZE)
    harness = args.harness.read_bytes()
    if not harness or len(harness) > VECTOR_BIOS_OFFSET:
        raise SystemExit("harness must fit before the injected exception vector")
    image[: len(harness)] = harness
    image[VECTOR_BIOS_OFFSET : VECTOR_BIOS_OFFSET + 0x80] = kernel[
        VECTOR_DRIVER_OFFSET : VECTOR_DRIVER_OFFSET + 0x80
    ]
    image[TABLE_SLOT_9_OFFSET : TABLE_SLOT_9_OFFSET + 4] = TABLE_HANDLER.to_bytes(
        4, "little"
    )
    if xsys is not None:
        image[XSYS_BIOS_OFFSET : XSYS_BIOS_OFFSET + len(xsys)] = xsys
        bridge = 0x08000000 | ((XSYS_ENTRY >> 2) & 0x03FFFFFF)
        image[HANDLER_BRIDGE_OFFSET : HANDLER_BRIDGE_OFFSET + 8] = (
            bridge.to_bytes(4, "little") + bytes(4)
        )

    # Malta's little-endian loader swaps complete words up to 0x3e0000.
    for offset in range(0, SWAP_SIZE, 4):
        image[offset : offset + 4] = image[offset : offset + 4][::-1]
    output_path.write_bytes(image)
    print(
        "MALTA BIOS IMAGE PASS "
        f"size=0x{len(image):x} vector=0xbfc08180 "
        f"table-slot9=0xbfc06f18 kernel-sha256={digest}"
        + (f" xsys-entry=0xbfc34074 xsys-sha256={xsys_digest}" if xsys else "")
    )


if __name__ == "__main__":
    main()
