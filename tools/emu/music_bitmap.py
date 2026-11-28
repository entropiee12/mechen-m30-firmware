"""Decode the evidence-backed direct RGB565 resources in ``music.sty``."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import struct


RU3_MAGIC = b"RU3\x19"
RESOURCE_TABLE_OFFSET = 0x400
RESOURCE_ENTRY_BYTES = 16
RGB565_BYTES_PER_PIXEL = 2
DIRECT_RGB565_FORMAT = 0x0002


@dataclass(frozen=True)
class MusicBitmap:
    """One ``music.sty`` bitmap with its raw RGB565 words."""

    resource_index: int
    file_offset: int
    size: int
    width: int
    height: int
    pixels: tuple[int, ...]


def load_music_bitmap(path: Path, resource_index: int) -> MusicBitmap:
    """Load one bounded direct-RGB565 resource without palette inference."""
    if resource_index < 0:
        raise ValueError("music.sty resource index must be non-negative")

    data = path.read_bytes()
    if data[:4] != RU3_MAGIC:
        raise ValueError(f"not an RU3 music resource: {path}")

    entry_offset = RESOURCE_TABLE_OFFSET + resource_index * RESOURCE_ENTRY_BYTES
    if entry_offset + RESOURCE_ENTRY_BYTES > len(data):
        raise ValueError("music.sty resource table entry is outside the member")

    file_offset, size, format_and_width, height = struct.unpack_from(
        "<IIII",
        data,
        entry_offset,
    )
    width = format_and_width >> 16
    format_flags = format_and_width & 0xFFFF

    if format_flags != DIRECT_RGB565_FORMAT:
        raise ValueError(
            f"unsupported music.sty bitmap format flags: 0x{format_flags:04x}"
        )
    if width == 0 or height == 0:
        raise ValueError("music.sty bitmap dimensions must be non-zero")

    expected_size = width * height * RGB565_BYTES_PER_PIXEL
    if size != expected_size:
        raise ValueError(
            "music.sty bitmap is not a direct 16-bit resource: "
            f"size={size}, expected={expected_size}"
        )
    if file_offset + size > len(data):
        raise ValueError("music.sty bitmap payload is outside the member")

    pixels = struct.unpack_from("<" + "H" * (size // 2), data, file_offset)
    return MusicBitmap(
        resource_index,
        file_offset,
        size,
        width,
        height,
        pixels,
    )
