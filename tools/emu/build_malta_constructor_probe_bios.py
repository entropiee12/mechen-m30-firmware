#!/usr/bin/env python3
"""Build Malta BIOS with real M30 constructor/vector and two RAM-base edits."""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path


BIOS_SIZE = 4 * 1024 * 1024
SWAP_SIZE = 0x3E0000
KERNEL_VECTOR_OFFSET = 0x380
VECTOR_BIOS_OFFSET = 0x8180
VECTOR_TABLE_LUI_DELTA = 0x34
INIT_BIOS_OFFSET = 0x32000
INIT_TABLE_LITERAL_OFFSET = 0x10DC
INIT_TABLE_BOUNDARY_OFFSET = 0x0C5C
TABLE_BOUNDARY_PC = 0xBFC01200
RAM_TABLE = 0x8001A9A4
BIOS_TABLE = 0x9FC1A9A4
BIOS_TABLE_LUI = 0x3C1A9FC2
RAM_TABLE_LUI = 0x3C1A8002
EXPECTED_KERNEL_SHA256 = "58f9d0c62bdbdab7cfeee1c5e65bbe8b88ee962f8eecd3dd0515d1a1056d8bd6"
EXPECTED_INIT_SHA256 = "f4c62848fb06ccbe6fba4323c4e29e46791c1abb25af648f0518eb0747492ec8"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("kernel_driver", type=Path)
    parser.add_argument("kernel_init", type=Path)
    parser.add_argument("harness", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    output_path = args.output.resolve()
    input_paths = {p.resolve() for p in (args.kernel_driver, args.kernel_init, args.harness)}
    aliases_input = output_path in input_paths
    if output_path.exists():
        aliases_input |= any(p.exists() and os.path.samefile(output_path, p)
                             for p in (args.kernel_driver, args.kernel_init, args.harness))
    if aliases_input:
        raise SystemExit("output path must not overwrite an input")

    kernel = args.kernel_driver.read_bytes()
    init = bytearray(args.kernel_init.read_bytes())
    digests = (hashlib.sha256(kernel).hexdigest(), hashlib.sha256(init).hexdigest())
    if digests != (EXPECTED_KERNEL_SHA256, EXPECTED_INIT_SHA256):
        raise SystemExit(f"firmware member SHA-256 mismatch: {digests}")
    if len(kernel) < KERNEL_VECTOR_OFFSET + 0x80:
        raise SystemExit("kernel.drv is truncated before the exception vector")
    if len(init) < INIT_TABLE_LITERAL_OFFSET + 4:
        raise SystemExit("ker_init.bin is truncated before the table-base literal")
    if INIT_BIOS_OFFSET + len(init) > BIOS_SIZE:
        raise SystemExit("ker_init.bin does not fit in the Malta BIOS window")

    table_literal = int.from_bytes(
        init[INIT_TABLE_LITERAL_OFFSET : INIT_TABLE_LITERAL_OFFSET + 4], "little"
    )
    if table_literal != BIOS_TABLE:
        raise SystemExit(f"unexpected ker_init table literal: 0x{table_literal:08x}")
    init[INIT_TABLE_LITERAL_OFFSET : INIT_TABLE_LITERAL_OFFSET + 4] = RAM_TABLE.to_bytes(
        4, "little"
    )
    expected_boundary = bytes.fromhex("c2 9f 03 3c 01 00 02 24")
    actual_boundary = init[
        INIT_TABLE_BOUNDARY_OFFSET : INIT_TABLE_BOUNDARY_OFFSET + len(expected_boundary)
    ]
    if actual_boundary != expected_boundary:
        raise SystemExit(f"unexpected constructor boundary bytes: {actual_boundary.hex()}")
    jump = 0x08000000 | ((TABLE_BOUNDARY_PC >> 2) & 0x03FFFFFF)
    init[INIT_TABLE_BOUNDARY_OFFSET : INIT_TABLE_BOUNDARY_OFFSET + 4] = jump.to_bytes(
        4, "little"
    )
    init[INIT_TABLE_BOUNDARY_OFFSET + 4 : INIT_TABLE_BOUNDARY_OFFSET + 8] = bytes(4)

    image = bytearray(b"\xff" * BIOS_SIZE)
    harness = args.harness.read_bytes()
    if not harness or len(harness) > VECTOR_BIOS_OFFSET:
        raise SystemExit("harness must fit before the exception vector")
    image[: len(harness)] = harness
    image[INIT_BIOS_OFFSET : INIT_BIOS_OFFSET + len(init)] = init

    vector = bytearray(kernel[KERNEL_VECTOR_OFFSET : KERNEL_VECTOR_OFFSET + 0x80])
    instruction_at = VECTOR_TABLE_LUI_DELTA
    instruction = int.from_bytes(vector[instruction_at : instruction_at + 4], "little")
    if instruction != BIOS_TABLE_LUI:
        raise SystemExit(f"unexpected vector table-base instruction: 0x{instruction:08x}")
    vector[instruction_at : instruction_at + 4] = RAM_TABLE_LUI.to_bytes(4, "little")
    image[VECTOR_BIOS_OFFSET : VECTOR_BIOS_OFFSET + len(vector)] = vector

    # QEMU Malta swaps each little-endian BIOS word before making its ROM copy.
    for offset in range(0, SWAP_SIZE, 4):
        image[offset : offset + 4] = image[offset : offset + 4][::-1]
    output_path.write_bytes(image)
    print(
        "MALTA CONSTRUCTOR BIOS PASS "
        f"constructor=0xbfc32bc8 vector=0xbfc08180 ram-table=0x{RAM_TABLE:08x} "
        f"kernel-sha256={digests[0]} init-sha256={digests[1]} "
        "patched-pointer-literals=2 instrumented-boundary=0xbfc32c5c"
    )


if __name__ == "__main__":
    main()
