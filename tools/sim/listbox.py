"""M30 directory-list rendering contract.

The Actions US212A SDK source in the public ATJ2127 reference project shows
that directory and menu views share the listbox driver.  The M30 common.sty
directory item has a 205-pixel text region beginning at x=35 and a 30-pixel row
height.  The host model uses a 50 ms active-scroll refresh interval so the
requested faster marquee cadence can be tested without claiming that the stock
firmware timer has been patched.  This model keeps those values explicit so UI
regressions can be tested without claiming to render the LCD or reproduce the
vendor font rasterizer.
"""

from __future__ import annotations

from dataclasses import dataclass


DIRECTORY_TEXT_X = 35
DIRECTORY_TEXT_WIDTH = 205
DIRECTORY_TEXT_HEIGHT = 16
DIRECTORY_ROW_HEIGHT = 30
DIRECTORY_HEAD_X = 5
DIRECTORY_SCROLL_PERIOD_MS = 50
DIRECTORY_GLYPH_WIDTH_PX = 8
# The SDK declares 52 bytes, but the M30 browser binary initializes each
# directory-list destination slot with 0x32 (50) bytes. The filesystem
# provider's output encoding and long-name behavior are not established.
NAME_BUFFER_BYTES = 50
UTF16_UNIT_BYTES = 2
UTF16_BOM_BYTES = 2
UTF16_TERMINATOR_BYTES = 2
LIST_SLOT_COUNT = 8  # title slot + seven visible directory rows
INFERRED_LIST_BUFFER_BYTES = 0x200
EXPERIMENTAL_NAME_BUFFER_BYTES = 64
LIST_BUFFER_ADDR = 0x40CD4C00
PATH_INFO_ADDR = 0x40CD4D90
MAX_NON_OVERLAPPING_NAME_BYTES = (PATH_INFO_ADDR - LIST_BUFFER_ADDR) // LIST_SLOT_COUNT
SCROLL_GAP_CHARS = 2
MARQUEE_SCAN_BYTES = 0x66


@dataclass(frozen=True)
class DirectoryItemStyle:
    text_x: int = DIRECTORY_TEXT_X
    text_width: int = DIRECTORY_TEXT_WIDTH
    text_height: int = DIRECTORY_TEXT_HEIGHT
    row_height: int = DIRECTORY_ROW_HEIGHT
    head_x: int = DIRECTORY_HEAD_X
    scroll_period_ms: int = DIRECTORY_SCROLL_PERIOD_MS
    glyph_width_px: int = DIRECTORY_GLYPH_WIDTH_PX


M30_DIRECTORY_STYLE = DirectoryItemStyle()


@dataclass(frozen=True)
class MarqueeFrame:
    """Deterministic state for one list-row marquee redraw.

    The vendor font is unavailable, so this model does not rasterize glyphs.
    It does preserve the important renderer contract: one-pixel offsets and
    image-backed restoration of the remaining edge region.
    """

    offset_px: int
    viewport_width_px: int
    clear_width_px: int
    background: str
    visible_text: str
    restore_mode: str = "sdimage-tail"


def marquee_frame(
    text: str,
    offset_px: int,
    viewport_width_px: int = DIRECTORY_TEXT_WIDTH,
    *,
    gap_chars: int = SCROLL_GAP_CHARS,
    style: DirectoryItemStyle = M30_DIRECTORY_STYLE,
    background: str = "selection-red",
) -> MarqueeFrame:
    """Model one pixel-positioned marquee frame and redraw region."""
    if viewport_width_px <= 0:
        raise ValueError("viewport_width_px must be positive")
    if style.glyph_width_px <= 0:
        raise ValueError("glyph_width_px must be positive")
    if gap_chars < 0:
        raise ValueError("gap_chars must be non-negative")
    if not text:
        return MarqueeFrame(0, viewport_width_px, viewport_width_px, background, "", "solid")

    stream = text + (" " * gap_chars)
    stream_width = len(stream) * style.glyph_width_px
    normalized = offset_px % stream_width
    first_cell = normalized // style.glyph_width_px
    cells = (stream + stream)[first_cell : first_cell + (viewport_width_px // style.glyph_width_px) + 2]
    return MarqueeFrame(
        offset_px=normalized,
        viewport_width_px=viewport_width_px,
        # The reference driver's clear_remain_pixel_sdimage() restores the
        # remaining edge, normally one pixel, from the selected-row bitmap.
        clear_width_px=1,
        background=background,
        visible_text=cells,
        restore_mode="sdimage-tail",
    )


def firmware_name_capacity_bytes() -> int:
    """Return the fixed per-entry name buffer used by the M30 binary."""
    return NAME_BUFFER_BYTES


def assumed_name_payload_bytes() -> int:
    """Return candidate UTF-16 space after an assumed BOM and terminator."""
    return NAME_BUFFER_BYTES - UTF16_BOM_BYTES - UTF16_TERMINATOR_BYTES


def assumed_directory_slot_text(name: str) -> str:
    """Apply the candidate BOM-and-terminator reserve to a directory name.

    The binary proves a 50-byte slot and a 25-unit request to the filesystem
    helper. It does not prove that the provider writes a BOM-prefixed string
    or reserves a terminator inside those 25 units. This preview hypothesis
    reserves both, yielding 46 bytes for BMP text. Iterate by Python
    characters so a non-BMP character is not split into a surrogate pair.
    """
    if not name:
        return ""
    limit = assumed_name_payload_bytes()
    used = 0
    visible: list[str] = []
    for char in name:
        encoded = char.encode("utf-16le")
        if used + len(encoded) > limit:
            break
        visible.append(char)
        used += len(encoded)
    return "".join(visible)


def format_directory_83_name(raw_name: bytes) -> bytes:
    """Apply the recovered M30 8.3 display conversion to one 12-byte slot.

    ``FUN_71352ab0`` leaves BOM-prefixed names untouched. For a plain 8.3
    result it inserts a dot after the space-trimmed eight-byte stem and
    terminates the visible string, while preserving the fixed 12-byte
    destination. The bytes after the terminator are intentionally retained.
    """
    if len(raw_name) != 12:
        raise ValueError("directory 8.3 names must occupy exactly 12 bytes")

    if raw_name[:2] == b"\xff\xfe":
        return bytes(raw_name)

    converted = bytearray(raw_name)
    for index in range(11, 8, -1):
        converted[index] = converted[index - 1]

    stem_end = 7
    while stem_end > 0 and converted[stem_end] == 0x20:
        stem_end -= 1

    output_end = stem_end + 1
    converted[output_end] = 0x2E

    for index in range(9, 12):
        if converted[index] != 0x20:
            output_end += 1
            converted[output_end] = converted[index]

    if converted[output_end] != 0x2E:
        output_end += 1

    converted[output_end] = 0
    return bytes(converted)


def prepare_marquee_utf16(buffer: bytes) -> bytes:
    """Prepare the bounded UTF-16 stream consumed by the marquee state.

    The recovered driver scans at most 0x66 bytes for a UTF-16 terminator,
    skips a leading BOM, and appends two spaces plus a terminator before the
    scroll loop starts. Keep this byte-oriented because the bound is a
    firmware buffer limit, not a Python character limit.
    """
    if len(buffer) % UTF16_UNIT_BYTES != 0:
        raise ValueError("UTF-16 marquee input must contain complete code units")

    scan_limit = min(len(buffer), MARQUEE_SCAN_BYTES)
    terminator_offset = None
    for offset in range(0, scan_limit, UTF16_UNIT_BYTES):
        code_unit = buffer[offset : offset + UTF16_UNIT_BYTES]

        if code_unit == b"\x00\x00":
            terminator_offset = offset
            break

    if terminator_offset is None:
        raise ValueError("UTF-16 marquee input has no terminator within 0x66 bytes")

    content = buffer[:terminator_offset]
    if content.startswith(b"\xff\xfe"):
        content = content[UTF16_BOM_BYTES:]

    gap = (" " * SCROLL_GAP_CHARS).encode("utf-16le")
    return content + gap + b"\x00\x00"


def experimental_name_layout() -> tuple[int, int]:
    """Return the opt-in slot size and total page use for the 64-byte test.

    The browser bank reserves the 0x200-byte page immediately before its
    0x40cd4e00 code bank for the directory list.  The caller also passes
    path_info at 0x40cd4d90, so 64 bytes * 8 overlaps that structure.  This
    function describes the deliberately unsafe analysis layout; it is not a
    production candidate.
    """
    used = LIST_SLOT_COUNT * EXPERIMENTAL_NAME_BUFFER_BYTES
    return EXPERIMENTAL_NAME_BUFFER_BYTES, used


def needs_active_scroll(text_width: int, style: DirectoryItemStyle = M30_DIRECTORY_STYLE) -> bool:
    """Match the SDK's long-string decision for the active list item."""
    return text_width > style.text_width


def scroll_window(
    text: str,
    frame: int,
    viewport_chars: int,
    gap_chars: int = SCROLL_GAP_CHARS,
) -> str:
    """Return a deterministic character approximation of the SDK marquee.

    The real driver uses measured glyph widths and appends two spaces before
    wrapping.  The emulator contract uses character cells so the state and
    redraw behavior can be tested independently of the proprietary font.
    """
    if viewport_chars <= 0:
        raise ValueError("viewport_chars must be positive")
    if gap_chars < 0:
        raise ValueError("gap_chars must be non-negative")
    if len(text) <= viewport_chars:
        return text
    stream = text + (" " * gap_chars)
    start = frame % len(stream)
    doubled = stream + stream
    return doubled[start : start + viewport_chars]


def active_redraw_period_ms(style: DirectoryItemStyle = M30_DIRECTORY_STYLE) -> int:
    return style.scroll_period_ms

