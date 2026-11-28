#!/usr/bin/env python3
"""Check the static boundary between ``usound.drv`` and the DAC driver.

The USB Audio member exposes a stream/ring path, but the available evidence
must not silently turn that into a codec-output claim. This probe records the
observed USB-side callback and word-port pool, and rejects a direct pointer
into the separate ``drv_dac.drv`` resident range.
"""

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
DAC_RUNTIME_RANGE = range(0xBFC18D00, 0xBFC19500)
USB_POOL_ADDRESS = 0xBFC31038
USB_POOL = (0x9FC34000, 0xBFC326A9, 0xBFC318CD)
WORD_PORT_POOL_ADDRESS = 0xBFC3194C
WORD_PORT_POOL = (0x9FC33E12, 0xC0080084, 0xC0080088)


def _file_offset(segment_file: int, segment_address: int, address: int) -> int:
    return segment_file + (address - segment_address)


def _aligned_words(data: bytes, start: int, length: int) -> tuple[int, ...]:
    return tuple(
        struct.unpack_from("<I", data, offset)[0]
        for offset in range(start, start + length - 3, 4)
    )


def verify(path: Path) -> None:
    image = YqhxImage.load(path)
    nonzero_segments = tuple(segment for segment in image.segments if segment.length)
    actual_segments = tuple(
        (segment.file_offset, segment.length, segment.destination)
        for segment in nonzero_segments
    )
    expected_segments = (USOUND_SEGMENT, USOUND_DATA_SEGMENT)
    if actual_segments != expected_segments:
        raise AssertionError(f"unexpected usound segments {actual_segments!r}")
    code_segment = nonzero_segments[0]

    data = path.read_bytes()
    direct_dac_refs = tuple(
        value for value in _aligned_words(data, code_segment.file_offset, code_segment.length)
        if value in DAC_RUNTIME_RANGE
    )
    if direct_dac_refs:
        raise AssertionError(f"unexpected direct drv_dac pointer(s): {direct_dac_refs!r}")

    usb_pool_offset = _file_offset(
        code_segment.file_offset,
        code_segment.destination,
        USB_POOL_ADDRESS,
    )
    actual_usb_pool = struct.unpack_from("<3I", data, usb_pool_offset)
    if actual_usb_pool != USB_POOL:
        raise AssertionError(f"USB callback pool {actual_usb_pool!r} != {USB_POOL!r}")

    port_pool_offset = _file_offset(
        code_segment.file_offset,
        code_segment.destination,
        WORD_PORT_POOL_ADDRESS,
    )
    actual_port_pool = struct.unpack_from("<3I", data, port_pool_offset)
    if actual_port_pool != WORD_PORT_POOL:
        raise AssertionError(
            f"USB word-port pool {actual_port_pool!r} != {WORD_PORT_POOL!r}"
        )

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("usound_driver", type=Path)
    args = parser.parse_args()
    verify(args.usound_driver)
    print(
        "USOUND DAC LINK PASS direct-dac-pointers=0 "
        "callback=0xbfc326a9 word-ports=0xc0080084,0xc0080088"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
