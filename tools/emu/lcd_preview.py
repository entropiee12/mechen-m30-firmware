#!/usr/bin/env python3
"""Compose the real-resource UI renderer with the recovered LCD sink.

This is a hybrid preview: the UI framebuffer is rendered on the host from
real ``music.ap``/``music.sty`` resources, then every pixel is sent through
the recovered LCD command/window and RGB565 boundary.  It is not a claim that
the ATJ2127 LCD controller or full firmware boot is emulated.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from lcd_transfer import LcdTransferBoundary
from m30_font import M30Font
from m30_ui import FirmwareTrace, MusicStyle, NowPlaying


def pack_rgb565(red: int, green: int, blue: int) -> int:
    """Quantize one host RGB888 pixel to the controller's RGB565 word."""

    # Use nearest quantization so a pixel expanded by ``rgb565`` can return
    # to its original word. Truncation would turn observed words such as
    # 0x18c3 into a different RGB565 value during the LCD round-trip.
    r = (max(0, min(255, red)) * 31 + 127) // 255
    g = (max(0, min(255, green)) * 63 + 127) // 255
    b = (max(0, min(255, blue)) * 31 + 127) // 255
    return (r << 11) | (g << 5) | b


def render_preview(
    music_ap: Path,
    music_sty: Path,
    *,
    title: str,
    artist: str,
    album: str,
    filename: str,
    scroll_offset: int,
) -> tuple[LcdTransferBoundary, NowPlaying]:
    trace = FirmwareTrace.load(music_ap)
    font_path = music_sty.with_name("new_m.fnt")
    font = M30Font.load(font_path) if font_path.exists() else None
    common_path = music_sty.with_name("common.sty")
    common_style = MusicStyle.load(common_path) if common_path.exists() else None
    ui = NowPlaying(
        MusicStyle.load(music_sty),
        trace,
        title=title,
        artist=artist,
        album=album,
        filename=filename,
        scroll_offset=scroll_offset,
        font=font,
        common_style=common_style,
    )
    source = ui.render()
    sink = LcdTransferBoundary()
    sink.set_orientation_value(0x48)
    sink.set_column_window(0, source.width - 1)
    sink.set_row_window(0, source.height - 1)
    sink.begin_memory_write()
    pixels = [
        pack_rgb565(source.pixels[offset], source.pixels[offset + 1], source.pixels[offset + 2])
        for offset in range(0, len(source.pixels), 3)
    ]
    sink.write_rgb565(pixels)
    if sink.remaining_pixels != 0:
        raise AssertionError("LCD preview left pixels outside the active window")
    return sink, ui


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--music-ap", type=Path, required=True)
    parser.add_argument("--music-sty", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--trace", type=Path)
    parser.add_argument("--title", default="TITLE")
    parser.add_argument("--artist", default="ARTIST")
    parser.add_argument("--album", default="ALBUM")
    parser.add_argument("--file", dest="filename", default="FILE")
    parser.add_argument("--offset", type=int, default=0)
    args = parser.parse_args()

    sink, ui = render_preview(
        args.music_ap,
        args.music_sty,
        title=args.title,
        artist=args.artist,
        album=args.album,
        filename=args.filename,
        scroll_offset=args.offset,
    )
    sink.framebuffer.ppm(args.out)
    if args.trace:
        args.trace.write_text(
            json.dumps(
                {
                    "commands": [
                        {"opcode": command.opcode, "payload": command.payload}
                        for command in sink.commands
                    ],
                    "pixels": sink.window.pixel_count if sink.window else 0,
                    "paint_dispatch": ui.paint_dispatch_trace(),
                    "ui_calls": ui.observed_dispatch_trace(),
                    "state_dispatch": ui.state_dispatch_traces(),
                    "header_status_resources": ui.header_status_trace(),
                },
                indent=2,
            )
            + "\n"
        )
    print(
        f"LCD PREVIEW PASS {args.out} commands="
        f"{','.join(f'0x{command.opcode:02x}' for command in sink.commands)} "
        f"pixels={sink.window.pixel_count if sink.window else 0}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
