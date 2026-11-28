#!/usr/bin/env python3
"""Check the extracted config item used by the USB-mode selector."""

from __future__ import annotations

import argparse
from pathlib import Path
import struct


CONFIG_ITEM_ID = 0x116
CONFIG_INDEX_OFFSET = 16 + CONFIG_ITEM_ID * 8
EXPECTED_PAYLOAD_OFFSET = 0xE4A
EXPECTED_PAYLOAD_LENGTH = 8
EXPECTED_PAYLOAD = bytes((0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00))


def verify(path: Path) -> None:
    data = path.read_bytes()
    if len(data) < CONFIG_INDEX_OFFSET + 8:
        raise AssertionError("config.bin is too short for the USB-mode index")
    payload_offset, payload_length = struct.unpack_from("<II", data, CONFIG_INDEX_OFFSET)
    if (payload_offset, payload_length) != (EXPECTED_PAYLOAD_OFFSET, EXPECTED_PAYLOAD_LENGTH):
        raise AssertionError(
            f"config item 0x{CONFIG_ITEM_ID:x} index "
            f"{payload_offset:#x},{payload_length:#x} != "
            f"{EXPECTED_PAYLOAD_OFFSET:#x},{EXPECTED_PAYLOAD_LENGTH:#x}"
        )
    payload = data[payload_offset : payload_offset + payload_length]
    if payload != EXPECTED_PAYLOAD:
        raise AssertionError(f"USB-mode payload {payload.hex()} != {EXPECTED_PAYLOAD.hex()}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("config_bin", type=Path)
    args = parser.parse_args()
    verify(args.config_bin)
    print(
        "USB MODE CONFIG PROBE PASS "
        "item=0x116 index=0x8c0 payload=0xe4a/8 "
        "record=0000000001000100 default-usound-flag=0"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
