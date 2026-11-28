"""Connect the proven application key filter to the host folder view."""

from __future__ import annotations

from dataclasses import asdict

from tools.sim.key_filter import (
    KEY_DOWN,
    KEY_NEXT,
    KEY_PREVIOUS,
    KeyEvent,
    KeyFilterState,
)

from folder_ui import FolderList
from scroll_accel import NEXT, PREVIOUS


class FolderInputSession:
    """Replay normalized key events against the evidence-backed folder view."""

    def __init__(self, view: FolderList, *, screen_state: int = 0) -> None:
        self.view = view
        self.key_filter = KeyFilterState(screen_state=screen_state)

    def dispatch(
        self,
        key_code: int,
        event_type: int,
        now_ms: int,
        *,
        patched: bool = False,
    ) -> dict[str, object]:
        """Filter one key event and apply navigation when the app receives it."""
        event = KeyEvent(key_code, event_type)
        decision = self.key_filter.process(event, patched=patched)
        result: dict[str, object] = {"decision": asdict(decision)}

        if decision.delivered is None or event_type != KEY_DOWN:
            result["action"] = decision.action
            return result

        direction = {
            KEY_NEXT: NEXT,
            KEY_PREVIOUS: PREVIOUS,
        }.get(key_code)
        if direction is None:
            result["action"] = "deliver"
            return result

        result["action"] = "navigate"
        result["navigation"] = self.view.move_event(direction, now_ms)
        return result


__all__ = ["FolderInputSession"]
