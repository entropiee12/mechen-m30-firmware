"""Read the evidence-backed directory colors and geometry from common.sty."""

from __future__ import annotations

import struct
from dataclasses import dataclass
from pathlib import Path

from m30_ui import rgb565


RU3_MAGIC = b"RU3\x19"
RESOURCE_TABLE_OFFSET = 0x400
RESOURCE_ENTRY_SIZE = 16
SELECTION_RESOURCE_INDEX = 80
LISTBOX_CONTROL_INDEX = 6


def _u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def _u16(data: bytes, offset: int) -> int:
    return struct.unpack_from("<H", data, offset)[0]


@dataclass(frozen=True)
class CommonStyle:
    """Exact values consumed by the host folder renderer.

    The font and proprietary bitmap renderer remain outside this parser. The
    values here are the resource-backed list geometry and colors, so a preview
    can use either the community red baseline or a real extracted UI variant.
    """

    path: Path
    selection_rgb565: int
    text_rgb565: int
    active_text_rgb565: int
    text_color: tuple[int, int, int]
    active_text_color: tuple[int, int, int]
    selection_color: tuple[int, int, int]
    text_x: int
    text_y: int
    text_width: int
    text_height: int
    row_height: int
    items_per_page: int

    @classmethod
    def load(cls, path: Path) -> "CommonStyle":
        data = path.read_bytes()
        if data[:4] != RU3_MAGIC:
            raise ValueError(f"not an RU3 resource file: {path}")

        style_addr = _u32(data, 8)
        style_begin = _u32(data, style_addr + 8)
        listbox_addr = _u32(data, style_begin + LISTBOX_CONTROL_INDEX * 8)
        fields = struct.unpack_from("<4I3H2B2H", data, listbox_addr)
        item_addr = fields[2]
        row_height = fields[7]
        items_per_page = fields[8]
        item = struct.unpack_from("<22H", data, item_addr)
        text_x, text_y = item[13], item[14]
        text_width, text_height = item[15], item[16]
        text_rgb565, active_text_rgb565 = item[17], item[18]

        entry_offset = RESOURCE_TABLE_OFFSET + SELECTION_RESOURCE_INDEX * RESOURCE_ENTRY_SIZE
        resource_offset, resource_size, _, _ = struct.unpack_from(
            "<4I", data, entry_offset
        )
        if not resource_offset or resource_size < 2 or resource_offset + resource_size > len(data):
            raise ValueError("common.sty has no valid selection resource 80")
        pixels = [
            _u16(data, resource_offset + offset)
            for offset in range(0, resource_size, 2)
        ]
        if len(set(pixels)) != 1:
            raise ValueError("selection resource 80 is not a solid color")
        selection_rgb565 = pixels[0]
        return cls(
            path=path,
            selection_rgb565=selection_rgb565,
            text_rgb565=text_rgb565,
            active_text_rgb565=active_text_rgb565,
            text_color=rgb565(text_rgb565),
            active_text_color=rgb565(active_text_rgb565),
            selection_color=rgb565(selection_rgb565),
            text_x=text_x,
            text_y=text_y,
            text_width=text_width,
            text_height=text_height,
            row_height=row_height,
            items_per_page=items_per_page,
        )
