#!/usr/bin/env python3
"""Reproduce the host-side USB descriptor patch from hash-checked M30 inputs."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import struct
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "sim"))

from usb_audio import patch_usound_descriptors  # noqa: E402


USOUND_SHA256 = "e593bdd29c2a96d4d745cf1b5cae4da2ee71c2211c551e415209b18f54e600fc"
CONFIG_SHA256 = "900f814711f50446802ee36bcc2d1560df55b9d2fb4e2d964bb35004517b6d19"
DEVICE_DESCRIPTOR_OFFSET = 0x2934
CONFIG_DESCRIPTOR_OFFSET = 0x2948
CONFIG_DESCRIPTOR_SIZE = 0x8A
CONFIG_INDEX_BASE = 0x10
CONFIG_ITEM_IDS = (0x107, 0x108, 0x10C, 0x10F, 0x114, 0x115)


def read_config_item(data: bytes, item_id: int) -> bytes:
    index_offset = CONFIG_INDEX_BASE + item_id * 8
    if index_offset + 8 > len(data):
        raise ValueError(f"config item 0x{item_id:x} index is outside config.bin")

    payload_offset, payload_size = struct.unpack_from("<II", data, index_offset)
    payload_end = payload_offset + payload_size
    if payload_end > len(data):
        raise ValueError(f"config item 0x{item_id:x} payload is outside config.bin")

    return data[payload_offset:payload_end]


def verify(usound_path: Path, config_path: Path) -> None:
    usound_data = usound_path.read_bytes()
    config_data = config_path.read_bytes()
    usound_sha256 = hashlib.sha256(usound_data).hexdigest()
    config_sha256 = hashlib.sha256(config_data).hexdigest()

    if usound_sha256 != USOUND_SHA256:
        raise AssertionError(f"usound.drv SHA-256 {usound_sha256} != {USOUND_SHA256}")

    if config_sha256 != CONFIG_SHA256:
        raise AssertionError(f"config.bin SHA-256 {config_sha256} != {CONFIG_SHA256}")

    device_end = DEVICE_DESCRIPTOR_OFFSET + 18
    configuration_end = CONFIG_DESCRIPTOR_OFFSET + CONFIG_DESCRIPTOR_SIZE
    if device_end > len(usound_data) or configuration_end > len(usound_data):
        raise ValueError("USB descriptor template extends beyond usound.drv")

    config_items = {
        item_id: read_config_item(config_data, item_id)
        for item_id in CONFIG_ITEM_IDS
    }
    result = patch_usound_descriptors(
        usound_data[DEVICE_DESCRIPTOR_OFFSET:device_end],
        usound_data[CONFIG_DESCRIPTOR_OFFSET:configuration_end],
        config_items,
    )

    if result.device_descriptor[8:12] != bytes((0xD6, 0x10, 0x01, 0xDD)):
        raise AssertionError("configured VID/PID differs from the p-code result")

    if result.string_descriptor(1) != bytes.fromhex("08 03 4d 00 33 00 30 00"):
        raise AssertionError("configured manufacturer string differs from the p-code result")

    if result.string_descriptor(2) != bytes.fromhex(
        "14 03 55 00 53 00 42 00 20 00 41 00 55 00 44 00 49 00 4f 00"
    ):
        raise AssertionError("configured product string differs from the p-code result")

    configuration = result.configuration_descriptor
    if result.sample_rate_hz != 48_000:
        raise AssertionError(f"unexpected configured sample rate {result.sample_rate_hz}")

    expected_fields = {
        0x36: 0x03,
        0x58: 0x02,
        0x59: 0x10,
        0x5B: 0x80,
        0x5C: 0xBB,
        0x5D: 0x00,
        0x65: 0xC8,
        0x66: 0x00,
    }
    for offset, expected in expected_fields.items():
        if configuration[offset] != expected:
            raise AssertionError(
                f"configuration byte +0x{offset:x} is 0x{configuration[offset]:02x}, "
                f"expected 0x{expected:02x}"
            )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("usound_driver", type=Path)
    parser.add_argument("config_bin", type=Path)
    args = parser.parse_args()
    verify(args.usound_driver, args.config_bin)
    print(
        "USOUND DESCRIPTOR PATCH PROBE PASS "
        "vid=10d6 pid=dd01 strings=M30/USB-AUDIO format=16bit/48000Hz packet=200 "
        "config-item-115-is-not-usb-mode-item-116"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
