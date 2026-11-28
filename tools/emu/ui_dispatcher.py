#!/usr/bin/env python3
"""Host implementation of the traced four-argument M30 UI dispatcher.

The firmware calls the indirect target at 0xbfc16405 with four arguments.
Only the operation envelope is known with high confidence at this stage, so
the shim records every call and implements the safe framebuffer operations.
Unknown operations are retained in the trace and return a deterministic
success value; they never mutate the framebuffer implicitly.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any

from m30_ui import (
    Framebuffer,
    MAIN_PAINT_STYLE,
    SECONDARY_STYLE,
    draw_marquee,
    draw_text,
    rgb565,
)


OP_SET_BACKGROUND_COLOR = 0x14
# Compatibility alias for callers that used the earlier trace-only name.
OP_CLEAR_COLOR = OP_SET_BACKGROUND_COLOR
OP_CLEAR_RECT = 0x12
OP_DRAW_CONTROL = 0x02
OP_DRAW_TEXT = 0x03
OP_DRAW_DYNAMIC = 0x06
OP_MEASURE = 0x0A


@dataclass(frozen=True)
class Rect:
    x: int
    y: int
    width: int
    height: int


@dataclass(frozen=True)
class TextPayload:
    text: str
    x: int
    y: int
    width: int
    height: int
    color: tuple[int, int, int] = (235, 235, 235)
    offset: int = 0


@dataclass(frozen=True)
class OpaqueMetadataPayload:
    """Carry the verified metadata payload without assigning its ABI."""

    table_value: int
    state_byte: int
    word_at_four: int = 0

    def trace(self) -> dict[str, int | str]:
        return {
            "table_value": self.table_value & 0xFFFF,
            "state_byte": self.state_byte & 0xFF,
            "word_at_four": self.word_at_four & 0xFFFFFFFF,
            "semantics": "opaque metadata payload",
        }


@dataclass(frozen=True)
class DispatcherCall:
    op: int
    style_id: int | None
    arg0_type: str
    arg1_type: str
    arg2: int
    result: int
    rendered: bool
    payload: dict[str, int | str] | None


class UiDispatcher:
    """Conservative host shim for target 0xbfc16405."""

    target_address = 0xBFC16405

    def __init__(self, framebuffer: Framebuffer | None = None) -> None:
        self.framebuffer = framebuffer or Framebuffer()
        self.background_color = rgb565(0x18C3)
        self.framebuffer.fill(self.background_color)
        self.calls: list[DispatcherCall] = []

    @staticmethod
    def _style_id(value: Any) -> int | None:
        if isinstance(value, int):
            return value & 0xFFFF
        if isinstance(value, dict) and "style_id" in value:
            return int(value["style_id"]) & 0xFFFF
        if hasattr(value, "style_id"):
            return int(value.style_id) & 0xFFFF
        return None

    def call(self, arg0: Any, arg1: Any, arg2: int, op: int) -> int:
        style_id = self._style_id(arg0)
        rendered = False
        payload = arg1.trace() if isinstance(arg1, OpaqueMetadataPayload) else None

        if op == OP_SET_BACKGROUND_COLOR:
            # The public SDK maps this operation to ui_set_backgd_color().
            # Keep the word as RGB565 until a real M30 resident consumer is
            # available; this is stronger than treating it as a clear call.
            if isinstance(arg0, int):
                self.background_color = rgb565(arg0 & 0xFFFF)
            rendered = True
        elif op == OP_CLEAR_RECT and isinstance(arg0, Rect):
            self.framebuffer.rect(
                arg0.x,
                arg0.y,
                arg0.width,
                arg0.height,
                self.background_color,
            )
            rendered = True
        elif op == OP_DRAW_TEXT and isinstance(arg1, TextPayload):
            clip = (arg1.x, arg1.y, arg1.width, arg1.height)
            draw_text(self.framebuffer, arg1.text, arg1.x - arg1.offset, arg1.y, arg1.color, 1, clip)
            rendered = True
        elif op == OP_DRAW_DYNAMIC and isinstance(arg1, TextPayload):
            draw_marquee(
                self.framebuffer,
                arg1.text,
                arg1.x,
                arg1.y,
                arg1.width,
                arg1.height,
                arg1.color,
                offset=arg1.offset,
            )
            rendered = True

        self.calls.append(
            DispatcherCall(
                op,
                style_id,
                type(arg0).__name__,
                type(arg1).__name__,
                arg2,
                1,
                rendered,
                payload,
            )
        )
        return 1

    def trace(self) -> list[dict[str, Any]]:
        return [asdict(call) for call in self.calls]


def replay_now_playing_dispatcher() -> UiDispatcher:
    """Replay the verified call envelope observed in the real scroll renderer."""
    dispatcher = UiDispatcher()
    dispatcher.call(0x18C3, 0, 0, OP_CLEAR_COLOR)
    dispatcher.call(0, 0, 0, OP_CLEAR_RECT)
    dispatcher.call(0x18C3, 0, 0, OP_CLEAR_COLOR)
    dispatcher.call(0, 0, 0, OP_CLEAR_RECT)
    dispatcher.call({"style_id": 0x1012}, 0, 0, OP_DRAW_CONTROL)
    dispatcher.call({"style_id": 0x1013}, 0, 0, OP_DRAW_CONTROL)
    dispatcher.call({"style_id": MAIN_PAINT_STYLE}, TextPayload("TITLE", 190, 40, 129, 20), 1, OP_DRAW_TEXT)
    dispatcher.call(0, TextPayload("TITLE", 190, 40, 129, 20), 6, OP_DRAW_DYNAMIC)
    dispatcher.call({"style_id": SECONDARY_STYLE}, TextPayload("TITLE", 190, 40, 129, 20), 1, OP_DRAW_TEXT)
    return dispatcher


def replay_now_playing_paint_dispatcher() -> UiDispatcher:
    """Replay the verified initial-paint color and rectangle sequence."""
    dispatcher = UiDispatcher()
    for x, y, width, height, color in (
        (0, 0, 320, 28, 0x18C3),
        (0, 28, 320, 151, 0x39E7),
        (0, 179, 320, 61, 0x18C3),
    ):
        dispatcher.call(color, 0, 0, OP_SET_BACKGROUND_COLOR)
        dispatcher.call(Rect(x, y, width, height), 0, 0, OP_CLEAR_RECT)
    dispatcher.call({"style_id": 0x1002}, 0, 0, OP_DRAW_CONTROL)
    return dispatcher
