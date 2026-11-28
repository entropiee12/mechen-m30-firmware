"""Host model of the M30 folder-list next/previous acceleration blob.

The model mirrors ``tools/scroll/accel.c`` rather than inventing a new UI
policy. It models the selection index, burst timing, and page-reload flags
that the patched MIPS16 handlers write. The input is a next/previous list
event; it is deliberately independent of whether the device generated that
event from an up/down key, wheel, or another control.
"""

from __future__ import annotations

from dataclasses import dataclass


GAP_MS = 700
FAST1_MS = 1000
FAST1_N = 6
FAST2_MS = 2500
FAST2_N = 16
FAST3_MS = 4000
FAST3_N = 26
FAST4_MS = 6000
FAST4_N = 40
MAX_STEPS = 100

NEXT = 1
PREVIOUS = 2


@dataclass
class BurstState:
    last_ms: int = 0
    start_ms: int = 0
    direction: int = 0
    count: int = 0


@dataclass(frozen=True)
class MoveResult:
    active: int
    requested_steps: int
    consumed_steps: int
    page_reload: bool
    mode: int | None
    update: int | None


class ScrollAccelerator:
    """Model ``accel_next`` and ``accel_prev`` from the real blob."""

    def __init__(self, state: BurstState | None = None) -> None:
        self.state = state or BurstState()

    def steps_for(self, direction: int, now_ms: int) -> int:
        if direction not in (NEXT, PREVIOUS):
            raise ValueError("direction must be NEXT or PREVIOUS")
        if now_ms < 0:
            raise ValueError("now_ms must be non-negative")

        state = self.state
        if direction != state.direction or now_ms - state.last_ms > GAP_MS:
            state.start_ms = now_ms
            state.count = 0
        state.direction = direction
        state.last_ms = now_ms
        state.count = min(state.count + 1, 0xFFFF)
        held_ms = now_ms - state.start_ms

        if held_ms < FAST1_MS and state.count < FAST1_N:
            return 1
        if held_ms < FAST2_MS and state.count < FAST2_N:
            return 5
        if held_ms < FAST3_MS and state.count < FAST3_N:
            return 20
        if held_ms < FAST4_MS and state.count < FAST4_N:
            return 50
        return MAX_STEPS

    @staticmethod
    def move_selection(active: int, total: int, direction: int, steps: int) -> tuple[int, int]:
        """Apply the stock one-step handler ``steps`` times."""
        if total < 1:
            raise ValueError("total must be positive")
        if not 1 <= active <= total:
            raise ValueError("active must be inside the list")
        if direction not in (NEXT, PREVIOUS):
            raise ValueError("direction must be NEXT or PREVIOUS")
        if steps < 0:
            raise ValueError("steps must be non-negative")

        consumed = 0
        for index in range(steps):
            before = active
            if direction == NEXT:
                if index and before >= total:
                    break
                active = 1 if active == total else active + 1
                if active < before:
                    consumed = index + 1
                    break
            else:
                if index and before <= 1:
                    break
                active = total if active == 1 else active - 1
                if active > before:
                    consumed = index + 1
                    break
            consumed = index + 1
        return active, consumed

    def move(self, active: int, total: int, direction: int, now_ms: int) -> MoveResult:
        requested = self.steps_for(direction, now_ms)
        new_active, consumed = self.move_selection(active, total, direction, requested)
        reload_page = consumed > 1
        return MoveResult(
            active=new_active,
            requested_steps=requested,
            consumed_steps=consumed,
            page_reload=reload_page,
            mode=4 if reload_page else None,
            update=2 if reload_page else None,
        )
