#!/usr/bin/env python3
"""Render a Ghidra-guided approximation of the M30 now-playing screen.

This is deliberately a host emulator, not a claim that qemu-system-mipsel can
boot the M30.  It consumes the real RU3 ``music.sty`` resource and validates
the real ``music.ap`` bank/call-site used by the shared marquee renderer.  The
small framebuffer renderer is useful for regression tests while the remaining
ATJ2127 UI dispatcher and LCD hardware are being modelled.
"""

from __future__ import annotations

import argparse
import json
import struct
from dataclasses import dataclass, field
from pathlib import Path

from m30_font import M30Font
from music_bitmap import MusicBitmap, load_music_bitmap


WIDTH, HEIGHT = 320, 240
SCROLL_VADDR = 0x40D55078
NOW_PLAYING_METADATA_TABLE_LITERAL = 0x40155338
SCROLL_STYLE = 0x2005
MAIN_PAINT_STYLE = 0x2005
SECONDARY_STYLE = 0x2004
RU3_MAGIC = b"RU3\x19"

PICBOX_FRAME_SEMANTICS = {
    1: {0: "shuffle-off", 1: "shuffle-on"},
    3: {0: "eq-hidden", 1: "eq-visible"},
    4: {0: "play", 1: "pause", 2: "next", 3: "previous"},
}

PICBOX_SEMANTICS = {
    5: "album-icon",
    6: "artist-icon",
    7: "title-icon",
    8: "track-icon",
}

# The supplied device photo shows these music.sty controls in the persistent
# header overlay.  They are not emitted by FUN_40154ddc itself, so the host
# model keeps the overlay separate from the now-playing paint trace.
OBSERVED_HEADER_FRAMES = ((1, 1), (0, 2))  # shuffle-on, repeat glyph
HEADER_EQ_TEXTBOX = 5
# These common.sty frames match the supplied device photo: the orange card,
# play triangle, and full battery. Their coordinates and bitmap references
# come from the resource PicBoxes; frame choices remain host-observed inputs.
COMMON_HEADER_STATUS_FRAMES = ((2, 2), (3, 2), (1, 6))

# FUN_40154ddc emits controls 0x1002..0x1008 on the now-playing refresh path.
# The p-code state probe dynamically confirms the 0x1000 + PicBox-index
# relationship for controls 0x1000..0x1004; the remaining metadata glyph
# controls retain the same resource-table convention from the static path.
# PicBoxes 0 and 1 are present in music.sty, but no call-site in that paint
# function selects them; drawing them here made the host preview show a pair
# of header icons that is absent from the device screenshot.
NOW_PLAYING_PICBOXES = (2, 3, 4, 5, 6, 7, 8)
PICBOX_CONTROL_IDS = {index: 0x1000 + index for index in range(9)}

# The real paint path sets a background color, then clears each of these
# rectangle regions. The color values are M30 RGB565 words observed in the
# MIPS16 call envelope; the public SDK independently names operation 0x14 as
# UI_SET_BACKGD_COLOR and operation 0x12 as UI_CLEARSCREEN.
NOW_PLAYING_CLEAR_BANDS = (
    (0, 0, 320, 28, 0x18C3),
    (0, 28, 320, 151, 0x39E7),
    (0, 179, 320, 61, 0x18C3),
)

# These flag-to-dispatch sequences come from the real state probe. The
# identifiers are intentionally kept as raw control/style IDs; their visual
# state selection is still supplied by the host inputs below.
NOW_PLAYING_STATE_DISPATCHES = {
    0x100: (
        "metadata",
        ((0x02, 0x1002, "control"), (0x03, 0x2005, "style")),
    ),
    0x020: (
        "transport",
        (
            (0x02, 0x1002, "control"),
            (0x02, 0x1004, "control"),
            (0x02, 0x1003, "control"),
        ),
    ),
    0x200: (
        "status-low",
        ((0x02, 0x1002, "control"), (0x02, 0x1000, "control")),
    ),
    0x800: (
        "status-high",
        ((0x02, 0x1002, "control"), (0x02, 0x1001, "control")),
    ),
    0x008: (
        "controls",
        (
            (0x02, 0x1002, "control"),
            (0x02, 0x1007, "control"),
            (0x02, 0x1006, "control"),
            (0x02, 0x1008, "control"),
            (0x02, 0x1005, "control"),
            (0x02, 0x1004, "control"),
            (0x02, 0x1002, "control"),
        ),
    ),
}

# Ghidra names these as _UNK_40d551xx because they are literal-pool function
# pointers.  These are the exact addresses read from the real AP bank.
DISPATCH_POINTERS = {
    0x40D5516C: "ui_dispatch",
    0x40D55170: "ui_read_status",
    0x40D55174: "ui_status_state",
    0x40D55178: "ui_set_backlight",
    0x40D5517C: "ui_restore_context",
    0x40D55180: "timer_ms",
    0x40D55198: "scroll_state",
    0x40D551B0: "scroll_state_byte",
    0x40D54F10: "key_read_begin",
    0x40D54F14: "key_read_event",
    0x40D54F18: "key_event_decode",
    0x40D54F1C: "key_event_fallback",
    0x40D54F20: "scroll_event_local",
    0x40D54F24: "key_poll",
    0x40D54F28: "key_event_callback",
}


def rgb565(value: int) -> tuple[int, int, int]:
    return (
        ((value >> 11) & 0x1F) * 255 // 31,
        ((value >> 5) & 0x3F) * 255 // 63,
        (value & 0x1F) * 255 // 31,
    )


def _u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def _style_resource_index(reference: int) -> int | None:
    if reference == 0xFFFF:
        return None
    return reference & 0x7FFF if reference & 0x8000 else reference


@dataclass(frozen=True)
class TextBox:
    style_id: int
    x: int
    y: int
    width: int
    height: int
    back_x: int
    back_y: int
    back_id: int
    text_color: int
    attrib: int


@dataclass(frozen=True)
class PicBox:
    style_id: int
    x: int
    y: int
    attrib: int
    frames: tuple[int, ...]


@dataclass(frozen=True)
class RawControl:
    kind: str
    words: tuple[int, ...]


@dataclass(frozen=True)
class MusicStyle:
    path: Path
    textboxes: tuple[TextBox, ...]
    picboxes: tuple[PicBox, ...]
    numboxes: tuple[RawControl, ...] = ()
    timeboxes: tuple[RawControl, ...] = ()
    progressbars: tuple[RawControl, ...] = ()

    @classmethod
    def load(cls, path: Path) -> "MusicStyle":
        data = path.read_bytes()
        if data[:4] != RU3_MAGIC:
            raise ValueError(f"not an RU3 resource: {path}")
        style_addr = _u32(data, 8)
        style_begin = _u32(data, style_addr + 8)
        pic_addr, pic_count = _u32(data, style_begin + 8), _u32(data, style_begin + 12)
        text_addr, text_count = _u32(data, style_begin + 16), _u32(data, style_begin + 20)
        num_addr, num_count = _u32(data, style_begin + 24), _u32(data, style_begin + 28)
        time_addr, time_count = _u32(data, style_begin + 32), _u32(data, style_begin + 36)
        progress_addr, progress_count = _u32(data, style_begin + 40), _u32(data, style_begin + 44)
        pics: list[PicBox] = []
        for i in range(pic_count):
            v = struct.unpack_from("<16H", data, pic_addr + i * 32)
            pics.append(PicBox(v[0], v[1], v[2], v[3], tuple(v[4:])))
        texts: list[TextBox] = []
        for i in range(text_count):
            v = struct.unpack_from("<10H", data, text_addr + i * 20)
            texts.append(TextBox(*v))
        def raw_controls(address: int, count: int, kind: str, size: int) -> tuple[RawControl, ...]:
            return tuple(
                RawControl(kind, tuple(struct.unpack_from(f"<{size // 2}H", data, address + i * size)))
                for i in range(count)
            )

        return cls(
            path,
            tuple(texts),
            tuple(pics),
            raw_controls(num_addr, num_count, "numbox", 28),
            raw_controls(time_addr, time_count, "timebox", 24),
            raw_controls(progress_addr, progress_count, "progressbar", 24),
        )

    def textboxes_by_geometry(self) -> tuple[TextBox, ...]:
        return tuple(sorted(self.textboxes, key=lambda item: (item.y, item.x)))

    def color(self, box: TextBox) -> tuple[int, int, int]:
        return rgb565(box.text_color)

    def bitmap(self, resource_index: int) -> MusicBitmap:
        return load_music_bitmap(self.path, resource_index)


@dataclass(frozen=True)
class Bank:
    file_offset: int
    virtual_address: int
    size: int


@dataclass(frozen=True)
class FirmwareTrace:
    ap_path: Path
    banks: tuple[Bank, ...]
    scroll_bank: Bank
    scroll_file_offset: int
    instruction: bytes
    scroll_style_id: int
    dispatch_targets: dict[str, int]
    metadata_table_address: int = 0
    metadata_table_values: tuple[int, ...] = ()
    source: str = "FUN_40d54f2c / shared scroll renderer"

    @classmethod
    def load(cls, path: Path) -> "FirmwareTrace":
        data = path.read_bytes()
        if data[4:8] != b"yqhx":
            raise ValueError(f"not an Actions .ap file: {path}")
        header_words = struct.unpack_from("<12I", data, 0x2C)
        banks: list[Bank] = []
        for offset in range(0x100, 0x100 + header_words[0] - 11, 12):
            file_offset, address, size = struct.unpack_from("<III", data, offset)
            if file_offset and address and size and file_offset + size <= len(data):
                banks.append(Bank(file_offset, address, size))
        bank = next((b for b in banks if b.virtual_address <= SCROLL_VADDR < b.virtual_address + b.size), None)
        if bank is None:
            raise ValueError(f"music.ap has no bank containing 0x{SCROLL_VADDR:08x}")
        file_offset = bank.file_offset + SCROLL_VADDR - bank.virtual_address
        instruction = data[file_offset:file_offset + 4]
        # MIPS16e: the audited call-site is ``li v0, 0x2005``.
        style_by_instruction = {
            bytes.fromhex("04 f0 05 6a"): 0x2005,
            bytes.fromhex("04 f0 01 6a"): 0x2001,
        }
        if instruction not in style_by_instruction:
            raise ValueError(
                f"unexpected scroll call-site at 0x{SCROLL_VADDR:08x}: "
                f"{instruction.hex(' ')}"
            )
        dispatch_targets: dict[str, int] = {}
        for address, name in DISPATCH_POINTERS.items():
            pointer_bytes = cls._read_virtual(data, banks, address, 4)
            dispatch_targets[name] = struct.unpack_from("<I", pointer_bytes)[0]
        metadata_table_literal = cls._read_virtual(
            data,
            banks,
            NOW_PLAYING_METADATA_TABLE_LITERAL,
            4,
        )
        metadata_table_address = struct.unpack_from("<I", metadata_table_literal)[0]
        metadata_table_bytes = cls._read_virtual(data, banks, metadata_table_address, 16)
        metadata_table_values = tuple(
            struct.unpack_from("<H", metadata_table_bytes, offset)[0]
            for offset in range(0, len(metadata_table_bytes), 2)
        )
        return cls(
            path,
            tuple(banks),
            bank,
            file_offset,
            instruction,
            style_by_instruction[instruction],
            dispatch_targets,
            metadata_table_address,
            metadata_table_values,
        )

    @staticmethod
    def _read_virtual(data: bytes, banks: list[Bank], address: int, size: int) -> bytes:
        for bank in banks:
            if bank.virtual_address <= address and address + size <= bank.virtual_address + bank.size:
                offset = bank.file_offset + address - bank.virtual_address
                return data[offset:offset + size]
        raise ValueError(f"no AP bank contains virtual range 0x{address:08x}+{size}")

    def json(self) -> dict[str, object]:
        return {
            "ap": str(self.ap_path),
            "source": self.source,
            "scroll_call": {
                "virtual_address": f"0x{SCROLL_VADDR:08x}",
                "file_offset": f"0x{self.scroll_file_offset:x}",
                "instruction": self.instruction.hex(" "),
                "style_id": f"0x{self.scroll_style_id:04x}",
            },
            "dispatch_targets": {
                name: f"0x{address:08x}" for name, address in self.dispatch_targets.items()
            },
            "now_playing_metadata_table": {
                "literal_address": f"0x{NOW_PLAYING_METADATA_TABLE_LITERAL:08x}",
                "table_address": f"0x{self.metadata_table_address:08x}",
                "values": [f"0x{value:04x}" for value in self.metadata_table_values],
                "semantics": "opaque state-indexed payload values",
            },
            "banks": [
                {"file_offset": f"0x{b.file_offset:x}", "virtual_address": f"0x{b.virtual_address:x}", "size": b.size}
                for b in self.banks
            ],
        }


@dataclass
class Framebuffer:
    width: int = WIDTH
    height: int = HEIGHT
    pixels: bytearray = field(default_factory=lambda: bytearray(WIDTH * HEIGHT * 3))

    def fill(self, color: tuple[int, int, int]) -> None:
        r, g, b = color
        self.pixels[:] = bytes((r, g, b)) * (self.width * self.height)

    def rect(self, x: int, y: int, width: int, height: int, color: tuple[int, int, int]) -> None:
        r, g, b = color
        x0, y0 = max(0, x), max(0, y)
        x1, y1 = min(self.width, x + width), min(self.height, y + height)
        for yy in range(y0, y1):
            start = (yy * self.width + x0) * 3
            self.pixels[start:(yy * self.width + x1) * 3] = bytes((r, g, b)) * (x1 - x0)

    def pixel(self, x: int, y: int, color: tuple[int, int, int]) -> None:
        if 0 <= x < self.width and 0 <= y < self.height:
            start = (y * self.width + x) * 3
            self.pixels[start:start + 3] = bytes(color)

    def ppm(self, path: Path) -> None:
        path.write_bytes(f"P6\n{self.width} {self.height}\n255\n".encode() + self.pixels)


# Compact 5x7 glyphs.  The emulator is for layout/scroll behaviour; it does
# not pretend to reproduce the proprietary M30 font rasterizer.
_FONT = {
    " ": (0, 0, 0, 0, 0),
    **{str(i): tuple(int(row, 2) for row in rows) for i, rows in {
        "0": ("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
        "1": ("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
        "2": ("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
        "3": ("11110", "00001", "00001", "01110", "00001", "00001", "11110"),
        "4": ("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
        "5": ("11111", "10000", "10000", "11110", "00001", "00001", "11110"),
        "6": ("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
        "7": ("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
        "8": ("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
        "9": ("01110", "10001", "10001", "01111", "00001", "00010", "11100"),
    }.items()},
}
for _char, _rows in {
    "A": ("01110", "10001", "10001", "11111", "10001", "10001", "10001"),
    "B": ("11110", "10001", "10001", "11110", "10001", "10001", "11110"),
    "C": ("01111", "10000", "10000", "10000", "10000", "10000", "01111"),
    "D": ("11110", "10001", "10001", "10001", "10001", "10001", "11110"),
    "E": ("11111", "10000", "10000", "11110", "10000", "10000", "11111"),
    "F": ("11111", "10000", "10000", "11110", "10000", "10000", "10000"),
    "G": ("01111", "10000", "10000", "10111", "10001", "10001", "01111"),
    "H": ("10001", "10001", "10001", "11111", "10001", "10001", "10001"),
    "I": ("11111", "00100", "00100", "00100", "00100", "00100", "11111"),
    "J": ("00111", "00010", "00010", "00010", "10010", "10010", "01100"),
    "K": ("10001", "10010", "10100", "11000", "10100", "10010", "10001"),
    "L": ("10000", "10000", "10000", "10000", "10000", "10000", "11111"),
    "M": ("10001", "11011", "10101", "10101", "10001", "10001", "10001"),
    "N": ("10001", "11001", "10101", "10011", "10001", "10001", "10001"),
    "O": ("01110", "10001", "10001", "10001", "10001", "10001", "01110"),
    "P": ("11110", "10001", "10001", "11110", "10000", "10000", "10000"),
    "Q": ("01110", "10001", "10001", "10001", "10101", "10010", "01101"),
    "R": ("11110", "10001", "10001", "11110", "10100", "10010", "10001"),
    "S": ("01111", "10000", "10000", "01110", "00001", "00001", "11110"),
    "T": ("11111", "00100", "00100", "00100", "00100", "00100", "00100"),
    "U": ("10001", "10001", "10001", "10001", "10001", "10001", "01110"),
    "V": ("10001", "10001", "10001", "10001", "10001", "01010", "00100"),
    "W": ("10001", "10001", "10001", "10101", "10101", "11011", "10001"),
    "X": ("10001", "10001", "01010", "00100", "01010", "10001", "10001"),
    "Y": ("10001", "10001", "01010", "00100", "00100", "00100", "00100"),
    "Z": ("11111", "00001", "00010", "00100", "01000", "10000", "11111"),
    ":": ("00000", "00100", "00100", "00000", "00100", "00100", "00000"),
    "-": ("00000", "00000", "00000", "11111", "00000", "00000", "00000"),
    "/": ("00001", "00010", "00100", "01000", "10000", "10000", "10000"),
}.items():
    _FONT[_char] = tuple(int(row, 2) for row in _rows)


def draw_text(
    fb: Framebuffer,
    text: str,
    x: int,
    y: int,
    color: tuple[int, int, int],
    scale: int = 2,
    clip: tuple[int, int, int, int] | None = None,
    cell_width: int | None = None,
) -> None:
    """Draw host glyphs with an optional fixed cell width.

    Now-playing uses the six-pixel host cell. Directory rows are measured by
    a separate Ghidra-guided model at eight pixels per glyph, so the folder
    renderer can reuse the same clipped rasterizer without implying that the
    proprietary font has been recovered.
    """
    if scale <= 0:
        raise ValueError("scale must be positive")
    if cell_width is None:
        cell_width = 6 * scale
    if cell_width < 5 * scale:
        raise ValueError("cell_width must fit the scaled glyph")
    for index, char in enumerate(text.upper()):
        glyph = _FONT.get(char, _FONT[" "])
        gx = x + index * cell_width
        for row, bits in enumerate(glyph):
            for col in range(5):
                if bits & (1 << (4 - col)):
                    for sy in range(scale):
                        for sx in range(scale):
                            px, py = gx + col * scale + sx, y + row * scale + sy
                            if clip and not (clip[0] <= px < clip[0] + clip[2] and clip[1] <= py < clip[1] + clip[3]):
                                continue
                            fb.pixel(px, py, color)


MARQUEE_GAP_PX = 12


def text_pixel_width(text: str, scale: int = 1) -> int:
    """Return the host model's fixed-cell width for one string.

    The proprietary font is not available to the emulator.  Keeping this
    measurement in one place still lets the renderer exercise the important
    contract: the scroll viewport is measured in pixels, not characters.
    """
    if scale <= 0:
        raise ValueError("scale must be positive")
    return len(text.upper()) * 6 * scale


def marquee_positions(
    text: str,
    offset: int,
    viewport_width: int,
    *,
    scale: int = 1,
    gap_px: int = MARQUEE_GAP_PX,
) -> tuple[int, ...]:
    """Return relative x positions for the visible copies of a marquee.

    A long string is repeated with a fixed gap, matching the verified
    two-space wrap in the firmware at the host model's six-pixel glyph width.
    Short strings remain stationary even when a later frame carries a shared
    scroll offset.  Positions are relative to the viewport's left edge and
    may be negative when a copy is leaving the viewport.
    """
    if viewport_width <= 0:
        raise ValueError("viewport_width must be positive")
    if gap_px < 0:
        raise ValueError("gap_px must be non-negative")
    width = text_pixel_width(text, scale)
    if not text or width <= viewport_width:
        return (0,) if text else ()

    cycle = width + gap_px
    normalized = offset % cycle
    positions: list[int] = []
    position = -normalized
    while position < viewport_width:
        if position + width > 0:
            positions.append(position)
        position += cycle
    return tuple(positions)


def draw_marquee(
    fb: Framebuffer,
    text: str,
    x: int,
    y: int,
    width: int,
    height: int,
    color: tuple[int, int, int],
    *,
    offset: int = 0,
    scale: int = 1,
    gap_px: int = MARQUEE_GAP_PX,
) -> None:
    """Draw a clipped, repeating text stream for one metadata viewport."""
    clip = (x, y, width, height)
    for relative_x in marquee_positions(text, offset, width, scale=scale, gap_px=gap_px):
        draw_text(fb, text, x + relative_x, y, color, scale, clip)


@dataclass
class NowPlaying:
    style: MusicStyle
    trace: FirmwareTrace
    title: str = "TITLE"
    artist: str = "ARTIST"
    album: str = "ALBUM"
    filename: str = "FILE"
    scroll_offset: int = 0
    transport_frame: int = 1
    eq_frame: int = 0
    status_frame: int = 0
    show_controls: bool = False
    elapsed: str = "00:18"
    track_position: str = "03/18"
    duration: str = "0:04:08"
    progress_fraction: float = 0.40
    font: M30Font | None = None
    common_style: MusicStyle | None = None
    common_status_frames: tuple[tuple[int, int], ...] = COMMON_HEADER_STATUS_FRAMES
    show_header: bool = True
    eq_label: str = "EQ: Jazz"
    volume_level: int = 6
    battery_percent: int = 100

    def dispatch_trace(self) -> list[dict[str, object]]:
        # This is the exact Ghidra-audited boundary that a future QEMU host
        # shim will receive from the real MIPS16 bank.
        return [
            {"kind": "ui_dispatch", "style_id": f"0x{MAIN_PAINT_STYLE:04x}", "source": "FUN_40154ddc", "text": self.title},
            {"kind": "ui_dispatch", "style_id": f"0x{self.trace.scroll_style_id:04x}", "source": self.trace.source, "text": self.title, "scroll_offset": self.scroll_offset},
        ]

    def metadata_clip_trace(self) -> list[dict[str, object]]:
        """Report clipping at each real metadata TextBox.

        Now-playing receives the complete value and lets the text renderer
        clip it to the 129px TextBox.  Use the same font measurement as the
        draw path when the extracted display font is available; the fixed-cell
        fallback remains explicit for resource-only host runs.
        """
        boxes = [box for box in self.style.textboxes if box.width == 129 and box.height == 20]
        values = (self.title, self.artist, self.album, self.filename)
        labels = ("title", "artist", "album", "file")
        result: list[dict[str, object]] = []
        for label, box, value in zip(labels, boxes, values):
            width = self.font.text_width(value, pixel_scale=1) if self.font else text_pixel_width(value)
            clipped = width > box.width
            result.append({
                "field": label,
                "text": value,
                "input_source": "full-value",
                "input_width_px": width,
                "viewport_width_px": box.width,
                "x": box.x,
                "y": box.y,
                "renderer_clipped": clipped,
                "clip_stage": "renderer-viewport" if clipped else "none",
                "scroll_input": "full-value",
                "scroll_eligible": clipped,
                "scroll_offset_px": self.scroll_offset,
            })
        return result

    def observed_dispatch_trace(self) -> list[dict[str, object]]:
        """Return the observed shared-scroll dispatcher envelope.

        Pointer payloads are represented by stable semantic labels here. Their
        stack addresses and unresolved internal fields deliberately stay out
        of the host contract. This is the separate ``0x1012``/``0x1013``
        scroll-renderer path, not the seven-call initial-paint path emitted by
        ``FUN_40154ddc``.
        """
        metadata_payload = self.metadata_payload_trace(metadata_index=0, state_byte=0)
        return [
            {"op": 0x14, "a0": 0x18C3, "a1": 0, "a2": 0, "role": "clear_background"},
            {"op": 0x12, "a0": 0, "a1": 0, "a2": 0, "role": "clear_content"},
            {"op": 0x14, "a0": 0x18C3, "a1": 0, "a2": 0, "role": "clear_background"},
            {"op": 0x12, "a0": 0, "a1": 0, "a2": 0, "role": "clear_content"},
            {"op": 0x02, "payload": "control_0x1012", "role": "draw_control"},
            {"op": 0x02, "payload": "control_0x1013", "role": "draw_control"},
            metadata_payload,
            {"op": 0x06, "a2": 6, "text": self.title, "scroll_offset": self.scroll_offset, "role": "draw_dynamic_text"},
            {"op": 0x03, "a2": 1, "style_id": f"0x{SECONDARY_STYLE:04x}", "text": self.title, "role": "draw_cleanup_text"},
        ]

    def paint_dispatch_trace(self) -> list[dict[str, object]]:
        """Return the verified seven-call initial-paint dispatcher envelope.

        The rectangle header is verified, while the remaining stack bytes
        contain pointers whose target meaning is not proven. The operation,
        scalar arguments, rectangle geometry, and final artwork control ID are
        stable across all twelve dated ``music.ap`` images.
        """
        calls: list[dict[str, object]] = []
        for x, y, width, height, background in NOW_PLAYING_CLEAR_BANDS:
            calls.append({
                "op": 0x14,
                "a0": background,
                "a1": 0,
                "a2": 0,
                "a3": 0x14,
                "role": "clear_background",
            })
            calls.append({
                "op": 0x12,
                "a0": 0,
                "a1": 0,
                "a2": 0,
                "a3": 0x12,
                "rectangle": {
                    "x": x,
                    "y": y,
                    "width": width,
                    "height": height,
                },
                "payload_tail": "opaque",
                "background_color": background,
                "semantics": "verified color and rectangle header; opaque payload tail",
                "role": "clear_content",
            })

        artwork = self.control_payload_trace(0x1002)
        artwork.update({
            "a0": 0,
            "a1": 0,
            "a2": 0,
            "a3": 0x02,
            "role": "draw_artwork",
        })
        calls.append(artwork)
        return calls

    def state_dispatch_trace(self, flags: int) -> list[dict[str, object]]:
        """Return one verified state-branch dispatcher sequence.

        The p-code probe executes one branch flag at a time and proves the
        operation order and raw identifier values. It does not prove the
        visual meaning of those identifiers, so this method deliberately
        stops at the dispatcher boundary.
        """
        branch = NOW_PLAYING_STATE_DISPATCHES.get(flags)
        if branch is None:
            raise ValueError(f"state flags have no verified dispatch trace: {flags:#x}")

        branch_name, dispatches = branch
        return [
            {
                "flags": flags,
                "branch": branch_name,
                "op": op,
                "identifier": identifier,
                "identifier_kind": identifier_kind,
                "semantics": "verified dispatcher boundary; visual state unresolved",
            }
            for op, identifier, identifier_kind in dispatches
        ]

    def state_dispatch_traces(self) -> dict[str, list[dict[str, object]]]:
        """Return every independently verified state-branch sequence."""
        return {
            branch_name: self.state_dispatch_trace(flags)
            for flags, (branch_name, _) in NOW_PLAYING_STATE_DISPATCHES.items()
        }

    def metadata_payload_trace(
        self,
        metadata_index: int,
        state_byte: int,
    ) -> dict[str, object]:
        """Expose the verified op-0x03 payload without assigning its meaning."""
        if not 0 <= metadata_index < len(self.trace.metadata_table_values):
            raise ValueError(f"metadata index is outside the observed table: {metadata_index}")

        return {
            "op": 0x03,
            "a2": 1,
            "style_id": f"0x{MAIN_PAINT_STYLE:04x}",
            "metadata_index": metadata_index,
            "metadata_index_source": "state+0x19",
            "table_value": self.trace.metadata_table_values[metadata_index],
            "payload_pointer_offset": 0x10,
            "state_byte": state_byte & 0xFF,
            "state_byte_source": "state+0x0e",
            "word_at_four": 0,
            "semantics": "opaque metadata payload",
            "role": "draw_active_text",
        }

    def control_payload_trace(self, control_id: int) -> dict[str, object]:
        """Expose the verified secondary payload for the primary controls."""
        supported_control_ids = (0x1000, 0x1001, 0x1002, 0x1003, 0x1004)
        if control_id not in supported_control_ids:
            raise ValueError(f"control ID has no verified secondary payload: {control_id:#x}")

        secondary_word = 0xFF00 if control_id in (0x1000, 0x1001) else 0xFFFF
        secondary_byte = 1 if control_id in (0x1003, 0x1004) else 0
        style_resource_type = 1 if control_id == 0x1003 else 0
        return {
            "op": 0x02,
            "control_id": control_id,
            "style_resource_type": style_resource_type,
            "payload_pointer_offset": 0x08,
            "secondary_payload_word": secondary_word,
            "secondary_payload_byte": secondary_byte,
            "semantics": "opaque control payload",
            "role": "draw_control",
        }

    def _draw_picbox_frame(self, fb: Framebuffer, picbox_index: int, frame_index: int) -> dict[str, object]:
        """Draw one real PicBox frame and return its provenance.

        The style records identify the pixels and their coordinates, but not
        the application state that selects a frame.  Keeping that selection
        explicit prevents the preview from presenting a guessed play/pause or
        progress state as if it came from the firmware.
        """
        picbox = self.style.picboxes[picbox_index]
        if not picbox.frames:
            raise ValueError(f"music.sty PicBox {picbox_index} has no frames")
        if not 0 <= frame_index < len(picbox.frames):
            raise ValueError(
                f"music.sty PicBox {picbox_index} frame {frame_index} is outside "
                f"the {len(picbox.frames)}-frame record"
            )
        resource_index = picbox.frames[frame_index]
        bitmap = self.style.bitmap(resource_index)
        if bitmap.width <= 0 or bitmap.height <= 0:
            raise ValueError(f"music.sty PicBox {picbox_index} has invalid bitmap dimensions")
        for row in range(bitmap.height):
            for column in range(bitmap.width):
                pixel = bitmap.pixels[row * bitmap.width + column]
                fb.pixel(picbox.x + column, picbox.y + row, rgb565(pixel))
        return {
            "picbox": picbox_index,
            "control_id": f"0x{PICBOX_CONTROL_IDS[picbox_index]:04x}",
            "frame": frame_index,
            "resource": resource_index,
            "x": picbox.x,
            "y": picbox.y,
            "width": bitmap.width,
            "height": bitmap.height,
            "state_semantics": PICBOX_FRAME_SEMANTICS.get(picbox_index, {}).get(
                frame_index, PICBOX_SEMANTICS.get(picbox_index, "unresolved")
            ),
        }

    def selected_picbox_trace(self) -> list[dict[str, object]]:
        painted = {2, 3, 5, 6, 7, 8}
        if self.show_controls:
            painted.add(4)
        if self.show_header:
            painted.update(index for index, _ in OBSERVED_HEADER_FRAMES)
        header_frames = dict(OBSERVED_HEADER_FRAMES) if self.show_header else {}
        selections = (
            (0, header_frames.get(0, self.status_frame)),
            (1, header_frames.get(1, self.status_frame)), (2, 0),
            (3, self.eq_frame), (4, self.transport_frame),
            (5, self.status_frame), (6, self.status_frame),
            (7, self.status_frame), (8, self.status_frame),
        )
        return [
            {
                "picbox": picbox_index,
                "control_id": f"0x{PICBOX_CONTROL_IDS[picbox_index]:04x}",
                "active_on_now_playing_paint": picbox_index in NOW_PLAYING_PICBOXES,
                "painted_in_preview": picbox_index in painted,
                "frame": frame_index,
                "resource": self.style.picboxes[picbox_index].frames[frame_index],
                "state_semantics": PICBOX_FRAME_SEMANTICS.get(picbox_index, {}).get(
                    frame_index, PICBOX_SEMANTICS.get(picbox_index, "unresolved")
                ),
            }
            for picbox_index, frame_index in selections
        ]

    @staticmethod
    def _draw_external_bitmap(fb: Framebuffer, style: MusicStyle, resource: int, x: int, y: int) -> None:
        bitmap = style.bitmap(resource)
        for row in range(bitmap.height):
            for column in range(bitmap.width):
                fb.pixel(x + column, y + row, rgb565(bitmap.pixels[row * bitmap.width + column]))

    def _draw_host_status_icons(self, fb: Framebuffer) -> None:
        """Draw observed header statuses using common.sty's real PicBoxes."""
        if self.common_style:
            self._draw_external_bitmap(fb, self.common_style, 3, 3, 2)   # speaker
            for picbox_index, frame_index in self.common_status_frames:
                picbox = self.common_style.picboxes[picbox_index]
                if not 0 <= frame_index < len(picbox.frames):
                    raise ValueError(
                        f"common.sty PicBox {picbox_index} has no frame {frame_index}"
                    )
                resource_index = _style_resource_index(picbox.frames[frame_index])
                if resource_index is None:
                    raise ValueError(
                        f"common.sty PicBox {picbox_index} frame {frame_index} is empty"
                    )
                self._draw_external_bitmap(
                    fb, self.common_style, resource_index, picbox.x, picbox.y
                )
        self._draw_device_numeric(fb, str(max(0, min(99, self.volume_level))), 27, 6, (25, 2, 28, 20))

    def header_status_trace(self) -> list[dict[str, object]]:
        """Return resource-backed status icons selected for the photo preview."""
        if not self.common_style:
            return []
        result = []
        for picbox_index, frame_index in self.common_status_frames:
            picbox = self.common_style.picboxes[picbox_index]
            resource_index = _style_resource_index(picbox.frames[frame_index])
            result.append({
                "picbox": picbox_index,
                "frame": frame_index,
                "resource": resource_index,
                "x": picbox.x,
                "y": picbox.y,
                "state_source": "host-observed device-photo state",
            })
        return result

    def _draw_header_overlay(self, fb: Framebuffer) -> None:
        if not self.show_header:
            return
        for picbox_index, frame_index in OBSERVED_HEADER_FRAMES:
            self._draw_picbox_frame(fb, picbox_index, frame_index)
        box = self.style.textboxes[HEADER_EQ_TEXTBOX]
        color = self.style.color(box)
        if self.font:
            ink_bounds = self.font.vertical_ink_bounds(self.eq_label, pixel_scale=1)
            text_y = box.y
            if ink_bounds:
                text_y = box.y + (box.height - (ink_bounds[1] - ink_bounds[0] + 1)) // 2 - ink_bounds[0]
            self.font.draw_text(fb, self.eq_label, box.x, text_y, color, clip=(box.x, box.y, box.width, box.height))
        else:
            draw_text(fb, self.eq_label, box.x, box.y + 6, color, clip=(box.x, box.y, box.width, box.height))
        self._draw_host_status_icons(fb)

    def _draw_device_numeric(self, fb: Framebuffer, text: str, x: int, y: int, clip: tuple[int, int, int, int]) -> None:
        """Draw time/track strings from the real 8x12 RU3 glyph resources."""
        resources = {str(index): 18 + index for index in range(10)}
        resources.update({":": 5, "/": 36})
        cursor = x
        for char in text:
            resource_index = resources.get(char)
            if resource_index is None:
                cursor += 8
                continue
            bitmap = self.style.bitmap(resource_index)
            for row in range(bitmap.height):
                for column in range(bitmap.width):
                    px, py = cursor + column, y + row
                    if clip[0] <= px < clip[0] + clip[2] and clip[1] <= py < clip[1] + clip[3]:
                        fb.pixel(px, py, rgb565(bitmap.pixels[row * bitmap.width + column]))
            cursor += bitmap.width

    def _numeric_pixel_width(self, text: str) -> int:
        """Measure a counter using the same native resources as its draw path."""
        resources = {str(index): 18 + index for index in range(10)}
        resources.update({":": 5, "/": 36})
        return sum(
            self.style.bitmap(resources[char]).width
            for char in text
            if char in resources
        )

    def _draw_time_and_progress(self, fb: Framebuffer) -> None:
        """Render the music.sty timebox/progressbar geometry.

        The numbox is the track-number counter above the footer controls.  The
        first six words of the first timebox are the observed left
        viewport (70,202,64,12,...).  The second timebox embeds the duration
        viewport at words 6..11 (235,202,64,12,...).  The progress resource
        is the 231x6 direct bitmap at resource 17; resources 31 and 32 are
        its one-pixel red/neutral fill columns.
        """
        if not self.style.timeboxes or not self.style.progressbars:
            return
        if self.style.numboxes:
            number = self.style.numboxes[0].words
            number_width = self._numeric_pixel_width(self.track_position)
            number_x = number[0] + max(0, (number[2] - number_width) // 2)
            self._draw_device_numeric(
                fb, self.track_position, number_x, number[1],
                (number[0], number[1], number[2], number[3]),
            )
        left = self.style.timeboxes[0].words
        self._draw_device_numeric(fb, self.elapsed, left[0], left[1], (left[0], left[1], left[2], left[3]))
        if len(self.style.timeboxes) > 1 and len(self.style.timeboxes[1].words) >= 10:
            right = self.style.timeboxes[1].words[6:]
            duration_width = self._numeric_pixel_width(self.duration)
            duration_x = right[0] + max(0, (right[2] - duration_width) // 2)
            self._draw_device_numeric(
                fb, self.duration, duration_x, right[1],
                (right[0], right[1], right[2], right[3]),
            )

        bar = self.style.progressbars[0].words
        bar_x, bar_y = bar[1], bar[2]
        background = self.style.bitmap(17)
        for row in range(background.height):
            for column in range(background.width):
                fb.pixel(bar_x + column, bar_y + row, rgb565(background.pixels[row * background.width + column]))
        fill = self.style.bitmap(31 if self.progress_fraction > 0 else 32)
        fill_width = max(0, min(background.width, round(background.width * self.progress_fraction)))
        for column in range(fill_width):
            for row in range(fill.height):
                fb.pixel(bar_x + column, bar_y + row, rgb565(fill.pixels[row * fill.width]))

    def render(self) -> Framebuffer:
        if not 0.0 <= self.progress_fraction <= 1.0:
            raise ValueError("progress_fraction must be between 0 and 1")
        fb = Framebuffer()
        # These are the three real clear regions emitted by FUN_40154ddc. The
        # middle band uses its own observed RGB565 background word.
        for x, y, width, height, color in NOW_PLAYING_CLEAR_BANDS:
            fb.rect(x, y, width, height, rgb565(color))

        self._draw_header_overlay(fb)

        # These are the actual music.sty PicBox coordinates/resources.  The
        # frame choice is a host input because the AP state machine is not yet
        # emulated; the bitmap bytes and geometry themselves are not guessed.
        controls = ((4, self.transport_frame),) if self.show_controls else ()
        metadata_icons = ((5, 0), (6, 0), (7, 0), (8, 0))
        for picbox_index, frame_index in ((2, 0), (3, self.eq_frame), *metadata_icons, *controls):
            self._draw_picbox_frame(fb, picbox_index, frame_index)

        # Metadata geometries are taken directly from textboxes 1..4.  The
        # corresponding PicBoxes 5..8 supply the row icons, so the host
        # preview starts each value at the shared x=190 viewport instead of
        # inventing red TITLE/ARTIST/ALBUM/TRACK labels.
        boxes = [box for box in self.style.textboxes if box.width == 129 and box.height == 20]
        values = (self.title, self.artist, self.album, self.filename)
        for box, value in zip(boxes, values):
            color = self.style.color(box)
            if self.font:
                ink_bounds = self.font.vertical_ink_bounds(value, pixel_scale=1)
                text_y = box.y
                if ink_bounds:
                    ink_height = ink_bounds[1] - ink_bounds[0] + 1
                    text_y = box.y + (box.height - ink_height) // 2 - ink_bounds[0]
                self.font.draw_marquee(
                    fb, value, box.x, text_y, box.width,
                    box.height, color, offset=self.scroll_offset, pixel_scale=1,
                )
            else:
                draw_marquee(
                    fb, value, box.x, box.y + 9, box.width,
                    box.height, color, offset=self.scroll_offset,
                )
        self._draw_time_and_progress(fb)
        return fb


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--music-ap", type=Path, required=True)
    parser.add_argument("--music-sty", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True, help="PPM framebuffer output")
    parser.add_argument("--trace", type=Path, help="write Ghidra/QEMU boundary trace as JSON")
    parser.add_argument("--title", default="TITLE")
    parser.add_argument("--artist", default="ARTIST")
    parser.add_argument("--album", default="ALBUM")
    parser.add_argument("--file", dest="filename", default="FILE")
    parser.add_argument("--offset", type=int, default=0)
    parser.add_argument("--transport-frame", type=int, default=1)
    parser.add_argument("--eq-frame", type=int, default=0)
    parser.add_argument("--status-frame", type=int, default=0)
    parser.add_argument("--show-controls", action="store_true", help="show transient transport/metadata controls")
    parser.add_argument("--elapsed", default="00:18")
    parser.add_argument("--track-position", default="03/18")
    parser.add_argument("--duration", default="0:04:08")
    parser.add_argument("--progress", dest="progress_fraction", type=float, default=0.40)
    parser.add_argument("--font", type=Path, help="M30 new_m.fnt; defaults to the style directory")
    args = parser.parse_args()
    trace = FirmwareTrace.load(args.music_ap)
    font_path = args.font or args.music_sty.with_name("new_m.fnt")
    font = M30Font.load(font_path) if font_path.exists() else None
    common_path = args.music_sty.with_name("common.sty")
    common_style = MusicStyle.load(common_path) if common_path.exists() else None
    ui = NowPlaying(
        MusicStyle.load(args.music_sty), trace, args.title, args.artist,
        args.album, args.filename, args.offset,
        transport_frame=args.transport_frame,
        eq_frame=args.eq_frame,
        status_frame=args.status_frame,
        show_controls=args.show_controls,
        elapsed=args.elapsed,
        track_position=args.track_position,
        duration=args.duration,
        progress_fraction=args.progress_fraction,
        font=font,
        common_style=common_style,
    )
    ui.render().ppm(args.out)
    if args.trace:
        args.trace.write_text(json.dumps({
            "firmware": trace.json(),
            "calls": ui.dispatch_trace(),
            "paint_dispatch": ui.paint_dispatch_trace(),
            "observed_dispatch": ui.observed_dispatch_trace(),
            "state_dispatch": ui.state_dispatch_traces(),
            "selected_picboxes": ui.selected_picbox_trace(),
            "header_status_resources": ui.header_status_trace(),
            "clipping": {
                "directory_input_contract": "50-byte slot and 25-unit provider request; provider output handling unknown",
                "metadata": ui.metadata_clip_trace(),
            },
            "resource_controls": {
                "numbox": [list(control.words) for control in ui.style.numboxes],
                "timebox": [list(control.words) for control in ui.style.timeboxes],
                "progressbar": [list(control.words) for control in ui.style.progressbars],
            },
        }, indent=2) + "\n")
    print(f"rendered {args.out} using {len(trace.banks)} real AP banks; scroll call 0x{SCROLL_VADDR:08x} -> style 0x{SCROLL_STYLE:04x}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
