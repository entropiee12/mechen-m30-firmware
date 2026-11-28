"""Byte-level boundary for the browser's verified 20-byte row record."""

from __future__ import annotations

from dataclasses import dataclass
import struct


ROW_RECORD_SIZE = 0x14
ROW_RECORD_BASE_OFFSET = 0x04
CONVERTER_SOURCE_OFFSET = 0x02


@dataclass(frozen=True)
class BrowserRowRecord:
    """One opaque row record with the only verified consumer field exposed.

    The firmware reads a record from ``list_state + 4 + row * 0x14`` and
    passes its little-endian halfword at ``+0x02`` to the text converter.
    The remaining bytes are intentionally kept opaque until their semantic
    ownership is proven.
    """

    raw: bytes

    @property
    def converter_source_halfword(self) -> int:
        """Return the record field passed as the converter's first argument."""
        return struct.unpack_from("<H", self.raw, CONVERTER_SOURCE_OFFSET)[0]


def decode_browser_row_record(data: bytes) -> BrowserRowRecord:
    """Decode one complete browser row record without assigning field names."""
    if len(data) != ROW_RECORD_SIZE:
        raise ValueError("browser row records must be exactly 20 bytes")

    return BrowserRowRecord(bytes(data))


def browser_row_record_offset(row_index: int) -> int:
    """Return the verified file offset for one row relative to list state."""
    if row_index < 0:
        raise ValueError("row_index must be non-negative")

    return ROW_RECORD_BASE_OFFSET + row_index * ROW_RECORD_SIZE
