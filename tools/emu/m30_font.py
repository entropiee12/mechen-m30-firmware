"""Read the sparse bitmap font shipped as ``new_m.fnt``.

The M30 font uses the Actions sparse-index layout: 1024 ten-byte entries at
0x10, followed by fixed-size glyph records at 0x2810.  This reader is
read-only and intentionally supports the ASCII subset needed by the screen
preview; it does not patch or rewrite the firmware font.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


MAGIC = b"FNT\x05"
INDEX_OFFSET = 0x10
INDEX_COUNT = 1024
INDEX_ENTRY_BYTES = 10
DATA_OFFSET = 0x2810
RECORD_BYTES = 61
ROW_BYTES = 2
ROWS_PER_RECORD = (RECORD_BYTES - 1) // ROW_BYTES
GLYPH_BITS = ROW_BYTES * 8
TRAILER_BYTES = 17

# The raw records are useful evidence but are not a reliable display font:
# several punctuation and lowercase records have ink outside their trailer
# advance, while I/J/M/W contain discontinuous bitmaps.  The host preview
# therefore uses this complete, deterministic metadata rasterizer.  It is
# intentionally display-only; glyph() still exposes the untouched firmware
# records for analysis and no font member is rewritten.
DISPLAY_PATTERNS = {
    " ": ("00000",) * 7,
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
    "A": ("01110", "10001", "10001", "11111", "10001", "10001", "10001"),
    "B": ("11110", "10001", "10001", "11110", "10001", "10001", "11110"),
    "C": ("01111", "10000", "10000", "10000", "10000", "10000", "01111"),
    "D": ("11110", "10001", "10001", "10001", "10001", "10001", "11110"),
    "E": ("11111", "10000", "10000", "11110", "10000", "10000", "11111"),
    "F": ("11111", "10000", "10000", "11110", "10000", "10000", "10000"),
    "G": ("01111", "10000", "10000", "10111", "10001", "10001", "01111"),
    "H": ("10001", "10001", "10001", "11111", "10001", "10001", "10001"),
    "I": ("01110", "00100", "00100", "00100", "00100", "00100", "01110"),
    "J": ("00111", "00010", "00010", "00010", "00010", "10010", "01100"),
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
    "a": ("00000", "00000", "01110", "00001", "01111", "10001", "01111"),
    "b": ("10000", "10000", "11110", "10001", "10001", "10001", "11110"),
    "c": ("00000", "00000", "01111", "10000", "10000", "10000", "01111"),
    "d": ("00001", "00001", "01111", "10001", "10001", "10001", "01111"),
    "e": ("00000", "00000", "01110", "10001", "11111", "10000", "01111"),
    "f": ("00110", "01001", "11100", "01000", "01000", "01000", "01000"),
    "g": ("00000", "00000", "01111", "10001", "01111", "00001", "01110"),
    "h": ("10000", "10000", "10110", "11001", "10001", "10001", "10001"),
    "i": ("00100", "00000", "01100", "00100", "00100", "00100", "01110"),
    "j": ("00010", "00000", "00110", "00010", "00010", "10010", "01100"),
    "k": ("10000", "10000", "10010", "10100", "11000", "10100", "10010"),
    "l": ("01100", "00100", "00100", "00100", "00100", "00100", "01110"),
    "m": ("00000", "00000", "11010", "10101", "10101", "10101", "10101"),
    "n": ("00000", "00000", "10110", "11001", "10001", "10001", "10001"),
    "o": ("00000", "00000", "01110", "10001", "10001", "10001", "01110"),
    "p": ("00000", "00000", "11110", "10001", "11110", "10000", "10000"),
    "q": ("00000", "00000", "01111", "10001", "01111", "00001", "00001"),
    "r": ("00000", "00000", "10110", "11001", "10000", "10000", "10000"),
    "s": ("00000", "00000", "01111", "10000", "01110", "00001", "11110"),
    "t": ("01000", "01000", "11100", "01000", "01000", "01001", "00110"),
    "u": ("00000", "00000", "10001", "10001", "10001", "10011", "01101"),
    "v": ("00000", "00000", "10001", "10001", "10001", "01010", "00100"),
    "w": ("00000", "00000", "10001", "10101", "10101", "11011", "01010"),
    "x": ("00000", "00000", "10001", "01010", "00100", "01010", "10001"),
    "y": ("00000", "00000", "10001", "10001", "01111", "00001", "01110"),
    "z": ("00000", "00000", "11111", "00010", "00100", "01000", "11111"),
    "-": ("00000", "00000", "00000", "11111", "00000", "00000", "00000"),
    ".": ("00000", "00000", "00000", "00000", "00000", "00100", "00100"),
    ":": ("00000", "00100", "00100", "00000", "00100", "00100", "00000"),
    "/": ("00001", "00010", "00100", "01000", "10000", "10000", "10000"),
    "?": ("01110", "10001", "00001", "00010", "00100", "00000", "00100"),
}

DISPLAY_ADVANCES = {char: 12 for char in DISPLAY_PATTERNS}
DISPLAY_ADVANCES.update({" ": 6, "I": 8, "i": 7, "j": 8, "l": 7, "-": 8, ".": 6, ":": 6, "/": 10})


@dataclass(frozen=True)
class FontGlyph:
    codepoint: int
    advance: int
    rows: tuple[int, ...]


class M30Font:
    def __init__(self, path: Path, data: bytes):
        self.path = path
        self.data = data

    @classmethod
    def load(cls, path: Path) -> "M30Font":
        data = path.read_bytes()
        if data[:4] != MAGIC:
            raise ValueError(f"not an M30 Actions font: {path}")
        if (
            len(data) < DATA_OFFSET + TRAILER_BYTES
            or (len(data) - DATA_OFFSET - TRAILER_BYTES) % RECORD_BYTES
        ):
            raise ValueError(f"unexpected new_m.fnt record layout: {path}")
        return cls(path, data)

    def _record_index(self, codepoint: int) -> int | None:
        if not 0 <= codepoint < INDEX_COUNT * 64:
            return None
        block, bit = divmod(codepoint, 64)
        offset = INDEX_OFFSET + block * INDEX_ENTRY_BYTES
        base = int.from_bytes(self.data[offset:offset + 2], "little")
        presence = int.from_bytes(self.data[offset + 2:offset + 10], "little")
        mask = 1 << bit
        if not presence & mask:
            return None
        return base + (presence & (mask - 1)).bit_count()

    def glyph(self, char: str) -> FontGlyph:
        if len(char) != 1:
            raise ValueError("glyph() accepts one character")
        codepoint = ord(char)
        index = self._record_index(codepoint)
        if index is None:
            if char != "?":
                return self.glyph("?")
            return FontGlyph(ord("?"), 8, (0,) * ROWS_PER_RECORD)
        offset = DATA_OFFSET + index * RECORD_BYTES
        record = self.data[offset:offset + RECORD_BYTES]
        rows = tuple(
            int.from_bytes(record[row * ROW_BYTES:(row + 1) * ROW_BYTES], "big")
            for row in range(ROWS_PER_RECORD)
        )
        return FontGlyph(codepoint, record[-1], rows)

    @staticmethod
    def _display_fallback(char: str) -> FontGlyph | None:
        pattern = DISPLAY_PATTERNS.get(char)
        if pattern is None and 32 <= ord(char) < 127:
            pattern = DISPLAY_PATTERNS["?"]
        if pattern is None:
            return None
        rows = [0] * ROWS_PER_RECORD
        for source_row, bits in enumerate(pattern):
            native_row = 1 + source_row * 2
            value = 0
            for source_column, bit in enumerate(bits):
                if bit == "1":
                    value |= 1 << (GLYPH_BITS - 1 - source_column * 2)
                    value |= 1 << (GLYPH_BITS - 2 - source_column * 2)
            rows[native_row] = value
            rows[native_row + 1] = value
        return FontGlyph(ord(char), DISPLAY_ADVANCES.get(char, DISPLAY_ADVANCES["?"]), tuple(rows))

    def display_glyph(self, char: str) -> FontGlyph:
        return self._display_fallback(char) or self.glyph(char)

    @staticmethod
    def _scaled_advance(advance: int, pixel_scale: int) -> int:
        return (advance + pixel_scale - 1) // pixel_scale

    def text_width(self, text: str, *, pixel_scale: int = 1) -> int:
        if pixel_scale not in (1, 2):
            raise ValueError("pixel_scale must be 1 or 2")
        return sum(self._scaled_advance(self.display_glyph(char).advance, pixel_scale) for char in text)

    def record_text_width(self, text: str, *, pixel_scale: int = 1) -> int:
        """Sum the advance bytes stored in the extracted font records.

        This is an evidence candidate for firmware text measurement, not a
        claim that a particular M30 screen path consumes these bytes.
        """
        if pixel_scale not in (1, 2):
            raise ValueError("pixel_scale must be 1 or 2")
        return sum(self._scaled_advance(self.glyph(char).advance, pixel_scale) for char in text)

    def vertical_ink_bounds(self, text: str, *, pixel_scale: int = 1) -> tuple[int, int] | None:
        """Return the displayed glyph ink's relative vertical bounds."""
        if pixel_scale not in (1, 2):
            raise ValueError("pixel_scale must be 1 or 2")
        bounds: list[int] = []
        for char in text:
            glyph = self.display_glyph(char)
            source_width = min(GLYPH_BITS, glyph.advance)
            for row in range(0, len(glyph.rows), pixel_scale):
                row_has_ink = any(
                    glyph.rows[source_row] & (1 << (GLYPH_BITS - 1 - source_column))
                    for column in range(0, source_width, pixel_scale)
                    for source_row in range(row, min(row + pixel_scale, len(glyph.rows)))
                    for source_column in range(column, min(column + pixel_scale, source_width))
                )
                if row_has_ink:
                    bounds.append(row // pixel_scale)
        return (min(bounds), max(bounds)) if bounds else None

    def draw_text(
        self,
        framebuffer,
        text: str,
        x: int,
        y: int,
        color: tuple[int, int, int],
        clip: tuple[int, int, int, int] | None = None,
        pixel_scale: int = 1,
    ) -> None:
        if pixel_scale not in (1, 2):
            raise ValueError("pixel_scale must be 1 or 2")
        cursor = x
        for char in text:
            glyph = self.display_glyph(char)
            # Records have a fixed 16-pixel row, but the trailer advance is
            # the glyph's actual native width.  Ignore right-side padding or
            # neighbouring bitmap data before downsampling; otherwise narrow
            # glyphs such as I paint into the next cell.
            source_width = min(GLYPH_BITS, glyph.advance)
            output_rows = range(0, len(glyph.rows), pixel_scale)
            output_columns = range(0, source_width, pixel_scale)
            for row in output_rows:
                for column in output_columns:
                    ink = any(
                        glyph.rows[source_row] & (1 << (GLYPH_BITS - 1 - source_column))
                        for source_row in range(row, min(row + pixel_scale, len(glyph.rows)))
                        for source_column in range(column, min(column + pixel_scale, source_width))
                    )
                    if not ink:
                        continue
                    px, py = cursor + column // pixel_scale, y + row // pixel_scale
                    if clip and not (clip[0] <= px < clip[0] + clip[2] and clip[1] <= py < clip[1] + clip[3]):
                        continue
                    framebuffer.pixel(px, py, color)
            cursor += self._scaled_advance(glyph.advance, pixel_scale)

    def draw_marquee(
        self,
        framebuffer,
        text: str,
        x: int,
        y: int,
        width: int,
        height: int,
        color: tuple[int, int, int],
        offset: int = 0,
        gap_px: int = 12,
        pixel_scale: int = 1,
    ) -> None:
        text_width = self.text_width(text, pixel_scale=pixel_scale)
        clip = (x, y, width, height)
        if not text or text_width <= width:
            positions = (0,) if text else ()
        else:
            cycle = text_width + gap_px
            normalized = offset % cycle
            positions = []
            position = -normalized
            while position < width:
                if position + text_width > 0:
                    positions.append(position)
                position += cycle
        for relative_x in positions:
            self.draw_text(framebuffer, text, x + relative_x, y, color, clip, pixel_scale=pixel_scale)
