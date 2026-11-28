"""Host model for the recovered multi-line text alignment contract.

The M30 UI driver measures a string before its word-splitting path runs.
The recovered character classifier treats LF, CR, and CRLF as line breaks.
This module models only that measurable contract; it does not claim to
reproduce the proprietary font or the complete line splitter.
"""

from __future__ import annotations

from dataclasses import dataclass


DEFAULT_GLYPH_WIDTH_PX = 8


@dataclass(frozen=True)
class TextAlignmentMeasurement:
    """The first-line measurement used by the centered-text calculation."""

    first_line: str
    true_length_px: int
    centered_x: int


def first_line(text: str) -> str:
    """Return text up to the first firmware-recognized line break."""
    line_break_index = len(text)

    for marker in ("\n", "\r"):
        marker_index = text.find(marker)

        if marker_index >= 0:
            line_break_index = min(line_break_index, marker_index)

    return text[:line_break_index]


def true_length_px(
    text: str,
    glyph_width_px: int = DEFAULT_GLYPH_WIDTH_PX,
) -> int:
    """Measure the first line using the host's fixed-cell font model."""
    if glyph_width_px <= 0:
        raise ValueError("glyph_width_px must be positive")

    return len(first_line(text)) * glyph_width_px


def centered_x(
    region_x: int,
    region_width_px: int,
    text_width_px: int,
) -> int:
    """Return the driver's integer x-coordinate for centered text."""
    if region_width_px <= 0:
        raise ValueError("region_width_px must be positive")
    if text_width_px < 0:
        raise ValueError("text_width_px must be non-negative")

    return region_x + (region_width_px - text_width_px) // 2


def measure_centered_text(
    text: str,
    region_x: int,
    region_width_px: int,
    glyph_width_px: int = DEFAULT_GLYPH_WIDTH_PX,
) -> TextAlignmentMeasurement:
    """Measure and center the first line of a multi-line string."""
    line = first_line(text)
    width = true_length_px(line, glyph_width_px)
    x = centered_x(region_x, region_width_px, width)

    return TextAlignmentMeasurement(
        first_line=line,
        true_length_px=width,
        centered_x=x,
    )
