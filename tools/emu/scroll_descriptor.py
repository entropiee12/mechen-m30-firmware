#!/usr/bin/env python3
"""Model the verified M30 shared-scroll descriptor boundary.

Ghidra shows FUN_40d54f2c receiving a pointer to a two-word context. The
first word points at a scroll state whose byte fields at +6/+7/+8 select the
number, active index, and previous index. The second word carries the small
status byte used by the renderer's event loop.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass, field


EVENT_NEXT = 0x2901
EVENT_PREV = 0x2902
EVENT_REDRAW = 0x2903
EVENT_EXIT = 0x2905
EVENT_CANCEL = 0x2906

STATUS_IDLE = 0
STATUS_REDRAW = 1
STATUS_SELECTION = 2
STATUS_INITIAL = 3


@dataclass
class ScrollEntry:
    style_id: int = 0x2005
    x: int = 0
    y: int = 0


@dataclass
class ScrollState:
    """Host representation of the fields read by FUN_40d54d80."""

    slot_count: int = 3
    active_slot: int = 0
    previous_slot: int = 0
    entries: list[ScrollEntry] = field(default_factory=list)

    def __post_init__(self) -> None:
        if not self.entries:
            self.entries = [ScrollEntry() for _ in range(self.slot_count)]
        if len(self.entries) != self.slot_count:
            raise ValueError("entries length must equal slot_count")
        if not 0 <= self.active_slot < self.slot_count:
            raise ValueError("active_slot outside slot_count")

    def select_next(self) -> None:
        if self.slot_count > 1:
            self.previous_slot = self.active_slot
            self.active_slot = (self.active_slot + 1) % self.slot_count

    def select_previous(self) -> None:
        if self.slot_count > 1:
            self.previous_slot = self.active_slot
            self.active_slot = (self.active_slot - 1) % self.slot_count

    def bytes_for(self, state_address: int = 0x10002000, entries_address: int = 0x10002100) -> tuple[bytearray, bytearray]:
        """Return state bytes and entries/context bytes for a MIPS harness."""
        state = bytearray(0x20)
        struct.pack_into("<I", state, 0x0C, entries_address)
        state[0x06] = self.slot_count & 0xFF
        state[0x07] = self.active_slot & 0xFF
        state[0x08] = self.previous_slot & 0xFF
        entries = bytearray(max(0x18, self.slot_count * 0x18))
        for i, entry in enumerate(self.entries):
            off = i * 0x18
            signed_style = entry.style_id if entry.style_id < 0x8000 else entry.style_id - 0x10000
            struct.pack_into("<h", entries, off + 0x00, signed_style)
            struct.pack_into("<h", entries, off + 0x04, entry.x)
            struct.pack_into("<h", entries, off + 0x08, entry.y)
        context = bytearray(8)
        struct.pack_into("<I", context, 0, state_address)
        return state, entries + context


@dataclass
class ScrollContext:
    state: ScrollState = field(default_factory=ScrollState)
    status: int = STATUS_INITIAL

    def apply_event(self, event: int) -> int:
        if event == EVENT_NEXT:
            self.state.select_next()
            self.status = STATUS_SELECTION
            return STATUS_SELECTION
        if event == EVENT_PREV:
            self.state.select_previous()
            self.status = STATUS_SELECTION
            return STATUS_SELECTION
        if event == EVENT_REDRAW:
            self.status = STATUS_REDRAW
            return STATUS_REDRAW
        if event == EVENT_EXIT:
            return -0x7FEF
        if event == EVENT_CANCEL:
            return -0x7FEC
        return 0


if __name__ == "__main__":
    context = ScrollContext()
    for event in (EVENT_NEXT, EVENT_PREV, EVENT_REDRAW):
        context.apply_event(event)
    state, entries_and_context = context.state.bytes_for()
    print(f"slots={state[0x06]} active={state[0x07]} previous={state[0x08]} serialized={len(state) + len(entries_and_context)}")
