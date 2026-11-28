#!/usr/bin/env python3
"""Parser and load-plan model for Actions ``yqhx`` driver images.

The resident kernel reads this header before copying a driver.  This module
models only the observable file-format contract; it never executes payload
bytes and is therefore safe to use in host-side tests.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass
from pathlib import Path


HEADER_SIZE = 0x4C
SEGMENT_COUNT = 4
MAGIC_OFFSET = 0x04


@dataclass(frozen=True)
class YqhxSegment:
    file_offset: int
    length: int
    destination: int


@dataclass(frozen=True)
class YqhxImage:
    path: Path
    header_offset: int
    segments: tuple[YqhxSegment, ...]
    bss_size: int
    bss_address: int
    entry: int
    entry_context: bytes

    @classmethod
    def parse(cls, path: Path, data: bytes, *, header_offset: int = 0) -> "YqhxImage":
        end = header_offset + HEADER_SIZE
        if end > len(data):
            raise ValueError("truncated yqhx header")
        if data[header_offset + MAGIC_OFFSET:header_offset + MAGIC_OFFSET + 4] != b"yqhx":
            raise ValueError(f"not a yqhx image: {path}")

        segments: list[YqhxSegment] = []
        for index in range(SEGMENT_COUNT):
            offset = header_offset + 0x08 + index * 0x0C
            file_offset, length, destination = struct.unpack_from("<III", data, offset)
            if length and file_offset + length > len(data):
                raise ValueError(
                    f"segment {index} exceeds image: 0x{file_offset:x}+0x{length:x}"
                )
            segments.append(YqhxSegment(file_offset, length, destination))

        bss_size, bss_address = struct.unpack_from("<II", data, header_offset + 0x38)
        entry = struct.unpack_from("<I", data, header_offset + 0x40)[0]
        entry_context = bytes(data[header_offset + 0x44:end])
        return cls(path, header_offset, tuple(segments), bss_size, bss_address, entry, entry_context)

    @classmethod
    def load(cls, path: Path, *, header_offset: int = 0) -> "YqhxImage":
        data = path.read_bytes()
        return cls.parse(path, data, header_offset=header_offset)

    def load_plan(self) -> tuple[tuple[str, int, int], ...]:
        """Return copy/zero operations as ``(kind, address, length)`` tuples."""

        operations = [
            ("segment", segment.destination, segment.length)
            for segment in self.segments
            if segment.length
        ]
        if self.bss_size:
            operations.append(("bss-zero", self.bss_address, self.bss_size))
        return tuple(operations)


def slot_layout(mode: int) -> tuple[str, int]:
    """Return the hypothesis slot layout for a mode byte.

    This is retained for the host model only. The current M30 kernel helpers
    at the corresponding candidate addresses are resident-table helpers, not
    a proven YQHX copy routine.
    """

    return ("compact", 4) if (mode & 0xFF) in (6, 7) else ("extended", 12)
