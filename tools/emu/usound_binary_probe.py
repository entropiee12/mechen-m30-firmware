#!/usr/bin/env python3
"""Verify the extracted USB Audio device descriptor and driver boundary."""

from __future__ import annotations

import argparse
from pathlib import Path
import struct
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "emu"))

from yqhx import YqhxImage  # noqa: E402


USOUND_SEGMENT = (0x200, 0x26B0, 0xBFC30000)
USOUND_DATA_SEGMENT = (0x2900, 0x154, 0x9FC33C00)
USOUND_BSS = (0x9FC33D54, 0xD4)
USOUND_ENTRY = 0xBFC32680
DEVICE_DESCRIPTOR = bytes(
    (0x12, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x40, 0xD6, 0x10, 0x00, 0xDD)
)
CONFIG_DESCRIPTOR = bytes((0x09, 0x02, 0x8A, 0x00, 0x03, 0x01, 0x00, 0x80, 0x32))
USB_AUDIO_FORMAT = bytes(
    (0x0E, 0x24, 0x02, 0x01, 0x02, 0x02, 0x10, 0x01, 0x80, 0xBB, 0x00, 0x00, 0x00, 0x00)
)
AUDIO_OUT_ENDPOINT = bytes((0x09, 0x05, 0x01, 0x09, 0xC8, 0x00, 0x01, 0x00, 0x00))
HID_ENDPOINT = bytes((0x07, 0x05, 0x82, 0x03, 0x04, 0x00, 0x20))
HID_REPORT_PREFIX = bytes((0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01))
AUDIO_INPUT_TERMINAL_ID = 1
AUDIO_INPUT_TERMINAL_TYPE = 0x0101  # USB streaming
AUDIO_OUTPUT_TERMINAL_ID = 6
AUDIO_OUTPUT_TERMINAL_TYPE = 0x0301  # speaker
AUDIO_OUTPUT_SOURCE_ID = 9
AUDIO_FEATURE_UNIT_ID = 9
AUDIO_FEATURE_SOURCE_ID = 1


def _descriptor_stream(data: bytes, offset: int) -> tuple[bytes, ...]:
    total = struct.unpack_from("<H", data, offset + 2)[0]
    end = offset + total
    if end > len(data):
        raise AssertionError("USB configuration exceeds driver image")
    descriptors: list[bytes] = []
    cursor = offset
    while cursor < end:
        length = data[cursor]
        if length < 2 or cursor + length > end:
            raise AssertionError(f"invalid USB descriptor at file offset 0x{cursor:x}")
        descriptors.append(data[cursor : cursor + length])
        cursor += length
    if cursor != end:
        raise AssertionError("USB descriptor stream does not end at wTotalLength")
    return tuple(descriptors)


def verify(path: Path) -> None:
    image = YqhxImage.load(path)
    actual_segments = tuple(
        (segment.file_offset, segment.length, segment.destination)
        for segment in image.segments
        if segment.length
    )
    expected_segments = (USOUND_SEGMENT, USOUND_DATA_SEGMENT)
    if actual_segments != expected_segments:
        raise AssertionError(
            f"USB Audio segments {actual_segments!r} != {expected_segments!r}"
        )
    if (image.bss_address, image.bss_size) != USOUND_BSS:
        raise AssertionError(
            f"USB Audio BSS {(image.bss_address, image.bss_size)!r} != {USOUND_BSS!r}"
        )
    if image.entry != USOUND_ENTRY:
        raise AssertionError(f"USB Audio entry 0x{image.entry:08x} != 0x{USOUND_ENTRY:08x}")

    data = path.read_bytes()
    device_offset = data.find(DEVICE_DESCRIPTOR)
    if device_offset < 0:
        raise AssertionError("USB Audio device descriptor not found")
    device = data[device_offset : device_offset + 18]
    if len(device) != 18 or device[0] != 0x12 or device[1] != 0x01:
        raise AssertionError("invalid USB Audio device descriptor")
    if struct.unpack_from("<H", device, 2)[0] != 0x0100:
        raise AssertionError("USB Audio descriptor is not USB 1.1")
    if struct.unpack_from("<H", device, 8)[0] != 0x10D6:
        raise AssertionError("unexpected USB Audio VID")
    if struct.unpack_from("<H", device, 10)[0] != 0xDD00:
        raise AssertionError("unexpected USB Audio PID")

    config_offset = data.find(CONFIG_DESCRIPTOR, device_offset + len(device))
    if config_offset < 0:
        raise AssertionError("USB Audio configuration descriptor not found")
    if data[config_offset : config_offset + len(CONFIG_DESCRIPTOR)] != CONFIG_DESCRIPTOR:
        raise AssertionError("USB Audio configuration descriptor header changed")
    descriptors = _descriptor_stream(data, config_offset)
    if len({descriptor[2] for descriptor in descriptors if descriptor[1] == 0x04}) != 3:
        raise AssertionError("USB Audio configuration does not expose three interfaces")
    interfaces = tuple(descriptor[2] for descriptor in descriptors if descriptor[1] == 0x04)
    if interfaces != (0, 1, 1, 3):
        raise AssertionError(f"unexpected USB interface sequence: {interfaces!r}")

    audio_control = tuple(
        descriptor for descriptor in descriptors if descriptor[1] == 0x24
    )
    input_terminals = tuple(
        descriptor
        for descriptor in audio_control
        if len(descriptor) >= 8 and descriptor[2] == 0x02
    )
    output_terminals = tuple(
        descriptor
        for descriptor in audio_control
        if len(descriptor) >= 9 and descriptor[2] == 0x03
    )
    feature_units = tuple(
        descriptor
        for descriptor in audio_control
        if len(descriptor) >= 5 and descriptor[2] == 0x06
    )
    if not any(
        descriptor[3] == AUDIO_INPUT_TERMINAL_ID
        and struct.unpack_from("<H", descriptor, 4)[0] == AUDIO_INPUT_TERMINAL_TYPE
        and descriptor[7] == 2
        for descriptor in input_terminals
    ):
        raise AssertionError("USB streaming input terminal topology is missing")
    if not any(
        descriptor[3] == AUDIO_OUTPUT_TERMINAL_ID
        and struct.unpack_from("<H", descriptor, 4)[0] == AUDIO_OUTPUT_TERMINAL_TYPE
        and descriptor[7] == AUDIO_OUTPUT_SOURCE_ID
        for descriptor in output_terminals
    ):
        raise AssertionError("speaker output terminal topology is missing")
    if not any(
        descriptor[3] == AUDIO_FEATURE_UNIT_ID and descriptor[4] == AUDIO_FEATURE_SOURCE_ID
        for descriptor in feature_units
    ):
        raise AssertionError("USB Audio feature-unit link is missing")

    required = {
        "audio-format": USB_AUDIO_FORMAT,
        "audio-out": AUDIO_OUT_ENDPOINT,
        "hid-in": HID_ENDPOINT,
    }
    missing = [name for name, marker in required.items() if marker not in data]
    if missing:
        raise AssertionError(f"missing USB Audio descriptor(s): {missing!r}")
    if HID_REPORT_PREFIX not in data:
        raise AssertionError("consumer-control HID report descriptor not found")
    for marker in (b"USB Audio", b"Actions"):
        if marker not in data:
            raise AssertionError(f"missing USB string marker: {marker!r}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("usb_audio_driver", type=Path)
    args = parser.parse_args()
    verify(args.usb_audio_driver)
    print(
        "USB AUDIO BINARY PROBE PASS "
        "usb=1.1 vid=10d6 pid=dd00 interfaces=0,1,1,3 "
        "pcm=2ch/16bit/48000Hz iso-out=0x01/200 "
        "speaker-terminal=0x0301 hid-in=0x82"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
