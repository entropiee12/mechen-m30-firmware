#!/usr/bin/env python3
"""Exact host model of the recovered browser/music list handlers.

This follows the Ghidra decompilation of FUN_487144a8 and FUN_487146cc.
Indices are one-based because that is how the firmware stores the list
descriptor.  It models the handler state transition and helper-call counts;
it does not render the LCD or emulate the resource dispatcher.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass
class FolderNavigationState:
    page_size: int
    start: int
    end: int
    total: int
    active: int
    anchor: int
    previous: int
    status: int = 0
    update: int = 0
    redraw: int = 0
    reset: int = 0

    def __post_init__(self) -> None:
        if self.page_size < 1 or self.total < 1:
            raise ValueError("page_size and total must be positive")
        if not 1 <= self.active <= self.total:
            raise ValueError("active must be inside the list")
        if self.start < 1 or self.end < self.start:
            raise ValueError("invalid initial list window")

    def snapshot(self) -> dict[str, int]:
        return {
            "start": self.start,
            "end": self.end,
            "active": self.active,
            "previous": self.previous,
            "status": self.status,
            "update": self.update,
            "redraw": self.redraw,
            "reset": self.reset,
        }

    def _finish(self) -> dict[str, int]:
        return self.snapshot()

    def next(self) -> dict[str, int]:
        step = 1
        self.redraw = 0
        self.reset = 0
        if self.end < self.active + step:
            if self.total == self.active:
                self.active = 1
                if self.page_size < self.total:
                    self.start = 1
                    self.end = self.page_size
                    self.anchor = 1
                    self.previous = 1
                    self.status = 4
                    self.update = 2
                elif 1 < self.total:
                    self.previous = self.anchor
                    self.anchor = 1
                    self.update = 1
            elif self.active < self.total - step:
                if self.anchor < self.page_size:
                    self.active += step
                    self.end = self.active
                    self.start = self.end - self.page_size + 1
                    self.anchor = self.page_size
                    self.previous = self.anchor
                else:
                    self.active += step
                    self.start += step
                    self.end += step
                    self.previous = self.anchor
                self.status = 1
                self.update = 2
            elif self.total - step == self.active and self.total == self.end:
                self.active += step
                self.previous = self.anchor
                self.anchor += step
                self.update = 1
            else:
                self.active = self.total
                self.end = self.total
                self.start = 1 if self.end < self.page_size else self.end - self.page_size + 1
                slot = self.end - self.start + 1
                self.anchor = slot
                self.previous = slot
                self.status = 1
                self.update = 2
        else:
            self.active += step
            self.previous = self.anchor
            self.anchor += step
            self.update = 1
        self.redraw = int(self.status == 1)
        return self._finish()

    def previous_item(self) -> dict[str, int]:
        step = 1
        self.redraw = 0
        self.reset = 0
        if step < self.anchor:
            self.active -= step
            self.previous = self.anchor
            self.anchor -= step
            self.update = 1
        elif self.active == 1:
            self.active = self.total
            if self.page_size < self.total:
                self.start = self.total - self.page_size + 1
                self.end = self.total
                self.anchor = self.page_size
                self.previous = self.anchor
                self.status = 5
                self.update = 2
            elif 1 < self.total:
                self.previous = self.anchor
                self.anchor = self.total
                self.update = 1
        elif step + 1 < self.active:
            if self.anchor < 2:
                self.active -= step
                self.start -= step
                self.end -= step
                self.previous = self.anchor
            else:
                self.active -= step
                self.start = self.active
                self.end = self.page_size + self.start - 1
                self.anchor = 1
                self.previous = 1
            self.status = 2
            self.update = 2
        elif step + 1 == self.active and self.start == 1:
            self.active -= step
            self.previous = self.anchor
            self.anchor -= step
            self.update = 1
        else:
            self.active = 1
            self.start = 1
            self.reset = 1
            self.status = 2
            self.update = 2
        self.redraw = int(self.status == 2)
        return self._finish()

    def apply(self, mode: str) -> dict[str, int]:
        if mode == "next":
            return self.next()
        if mode == "previous":
            return self.previous_item()
        raise ValueError("mode must be next or previous")
