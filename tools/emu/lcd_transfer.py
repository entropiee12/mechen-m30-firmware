"""Evidence-backed host boundary for the recovered M30 LCD transfer path.

The recovered driver programs controller orientation with command 0x36,
column and row windows with commands 0x2a and 0x2b, then starts a memory write
with command 0x2c.  This module models those raw command boundaries and the
RGB565 pixel transfer.  Controller registers, orientation semantics,
interrupts, and panel electrical behavior remain outside the host contract.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from m30_ui import Framebuffer, rgb565


COLUMN_ADDRESS_SET = 0x2A
ROW_ADDRESS_SET = 0x2B
MEMORY_WRITE = 0x2C
MEMORY_ACCESS_CONTROL = 0x36

# FUN_bfc0a438 selects these raw controller values.  Their physical
# orientation names are not established by the recovered driver.
RECOVERED_ORIENTATION_VALUES = (0xC8, 0xA8, 0x48, 0xE8, 0xE8, 0x88)


@dataclass(frozen=True)
class LcdWindow:
    """Inclusive column and row bounds sent to the LCD controller."""

    column_start: int
    column_end: int
    row_start: int
    row_end: int

    @property
    def pixel_count(self) -> int:
        return (self.column_end - self.column_start + 1) * (
            self.row_end - self.row_start + 1
        )


@dataclass(frozen=True)
class LcdCommand:
    """One command-level event retained for host regression traces."""

    opcode: int
    payload: tuple[int, ...] = ()


@dataclass
class LcdTransferBoundary:
    """Apply a bounded LCD memory-write stream to a host framebuffer."""

    framebuffer: Framebuffer = field(default_factory=Framebuffer)
    commands: list[LcdCommand] = field(default_factory=list)
    _column_start: int | None = field(default=None, init=False, repr=False)
    _column_end: int | None = field(default=None, init=False, repr=False)
    _row_start: int | None = field(default=None, init=False, repr=False)
    _row_end: int | None = field(default=None, init=False, repr=False)
    _write_started: bool = field(default=False, init=False, repr=False)
    _cursor: int = field(default=0, init=False, repr=False)

    @property
    def window(self) -> LcdWindow | None:
        values = (
            self._column_start,
            self._column_end,
            self._row_start,
            self._row_end,
        )
        if any(value is None for value in values):
            return None

        return LcdWindow(*values)

    @property
    def remaining_pixels(self) -> int:
        window = self.window
        if window is None or not self._write_started:
            return 0

        return window.pixel_count - self._cursor

    def set_column_window(self, start: int, end: int) -> None:
        self._validate_coordinate_pair(start, end, "column")
        self._column_start = start
        self._column_end = end
        self._write_started = False
        self._cursor = 0
        self.commands.append(LcdCommand(COLUMN_ADDRESS_SET, (start, end)))

    def set_orientation_value(self, value: int) -> None:
        """Record one raw orientation value observed in the LCD driver."""
        if value not in RECOVERED_ORIENTATION_VALUES:
            raise ValueError("orientation value is not present in recovered LCD code")

        self.commands.append(LcdCommand(MEMORY_ACCESS_CONTROL, (value,)))

    def set_row_window(self, start: int, end: int) -> None:
        self._validate_coordinate_pair(start, end, "row")
        self._row_start = start
        self._row_end = end
        self._write_started = False
        self._cursor = 0
        self.commands.append(LcdCommand(ROW_ADDRESS_SET, (start, end)))

    def begin_memory_write(self) -> None:
        if self.window is None:
            raise ValueError("column and row windows must be set before memory write")

        self._write_started = True
        self._cursor = 0
        self.commands.append(LcdCommand(MEMORY_WRITE))

    def write_rgb565(self, pixels: list[int] | tuple[int, ...]) -> int:
        window = self.window
        if not self._write_started or window is None:
            raise ValueError("memory write must be started before pixel data")

        if any(pixel < 0 or pixel > 0xFFFF for pixel in pixels):
            raise ValueError("RGB565 pixels must fit one 16-bit word")

        if len(pixels) > self.remaining_pixels:
            raise ValueError("pixel stream exceeds the active LCD window")

        window_width = window.column_end - window.column_start + 1
        for pixel in pixels:
            x = window.column_start + self._cursor % window_width
            y = window.row_start + self._cursor // window_width
            self.framebuffer.pixel(x, y, rgb565(pixel))
            self._cursor += 1

        return len(pixels)

    @staticmethod
    def _validate_coordinate_pair(start: int, end: int, axis: str) -> None:
        if not 0 <= start <= 0xFFFF or not 0 <= end <= 0xFFFF:
            raise ValueError(f"{axis} coordinates must fit one 16-bit word")

        if start > end:
            raise ValueError(f"{axis} window start must not exceed its end")
