#!/usr/bin/env python3
"""Verify the raw MIPS16 clear-call envelope in ``drv_ui.drv``."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
from pathlib import Path


EXPECTED_SHA256 = "011f4e6a50738026ac641370f493c5df2e59adc435871d8a213e717604d2576a"
CALL_OFFSET = 0x96B4
CALL_BYTES = bytes.fromhex("a1 b4 a2 b1 01 6f 00 6d 40 e9 7c 6e")
POOL_OFFSET = 0x9938
POOL_BYTES = bytes.fromhex("2c bd c0 9f 01 9c c0 bf")
MODULE_10_STUB_OFFSET = 0x1200
MODULE_10_STUB_BYTES = bytes.fromhex("10 6b 05 e8")


@dataclass(frozen=True)
class MemoryCallEvidence:
    destination: int
    target: int
    value: int
    length: int
    operation: int


def inspect_bytes(data: bytes) -> MemoryCallEvidence:
    """Check instruction and literal bytes; leave the ROM service effect opaque."""
    if len(data) <= MODULE_10_STUB_OFFSET + len(MODULE_10_STUB_BYTES):
        raise AssertionError("drv_ui.drv is too short for the module-0x10 stub")

    call_end = CALL_OFFSET + len(CALL_BYTES)
    if len(data) < call_end:
        raise AssertionError("drv_ui.drv is too short for the clear-call sequence")

    actual_call = data[CALL_OFFSET:call_end]
    if actual_call != CALL_BYTES:
        raise AssertionError(
            f"clear-call bytes {actual_call.hex()} != {CALL_BYTES.hex()}"
        )

    pool_end = POOL_OFFSET + len(POOL_BYTES)
    if len(data) < pool_end:
        raise AssertionError("drv_ui.drv is too short for the clear-call literal pool")

    actual_pool = data[POOL_OFFSET:pool_end]
    if actual_pool != POOL_BYTES:
        raise AssertionError(
            f"clear-call pool {actual_pool.hex()} != {POOL_BYTES.hex()}"
        )

    stub_end = MODULE_10_STUB_OFFSET + len(MODULE_10_STUB_BYTES)
    actual_stub = data[MODULE_10_STUB_OFFSET:stub_end]
    if actual_stub != MODULE_10_STUB_BYTES:
        raise AssertionError(
            f"module-0x10 stub {actual_stub.hex()} != {MODULE_10_STUB_BYTES.hex()}"
        )

    destination = int.from_bytes(actual_pool[:4], "little")
    target = int.from_bytes(actual_pool[4:], "little")
    return MemoryCallEvidence(
        destination=destination,
        target=target,
        value=0,
        length=0x7C,
        operation=1,
    )


def verify(path: Path) -> MemoryCallEvidence:
    data = path.read_bytes()
    actual_sha256 = hashlib.sha256(data).hexdigest()
    if actual_sha256 != EXPECTED_SHA256:
        raise AssertionError(
            f"drv_ui.drv SHA-256 {actual_sha256} != {EXPECTED_SHA256}"
        )

    evidence = inspect_bytes(data)
    if (evidence.destination, evidence.target) != (0x9FC0BD2C, 0xBFC09C01):
        raise AssertionError(f"unexpected clear-call pointers: {evidence!r}")

    return evidence


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("driver", type=Path)
    args = parser.parse_args()
    evidence = verify(args.driver)
    print(
        "DRV_UI CLEAR-CALL BINARY PROBE PASS "
        f"dst=0x{evidence.destination:08x} target=0x{evidence.target:08x} "
        f"value={evidence.value} length={evidence.length} "
        f"operation={evidence.operation} service-effect=unverified"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
