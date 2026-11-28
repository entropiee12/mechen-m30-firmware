"""Fail-closed trace model for the recovered M30 upgrade boundary.

The extracted ``upgrade.ap`` proves the five-option screen, several entry-side
selector values, and a decrypt/search preflight area.  It does not contain the
fixed-ROM dispatcher or prove the order/meaning of the indirect returns.  This
module therefore records only observed boundary events; it never produces a
flash, reboot, or successful-update event.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
from enum import Enum
from typing import Any


OPTION_COUNT = 5
ENTRY_SELECTORS = (0x10, 0x33, 0x4A)
FAILURE_SELECTORS = (0x11, 0x34)


class UpgradePhase(str, Enum):
    SELECT = "select"
    CONFIRM_REQUEST = "confirm-request"
    DECRYPT_ERROR = "decrypt-error"
    SEARCH_COMPLETE = "search-complete"


@dataclass(frozen=True)
class UpgradeTraceEvent:
    kind: str
    phase: str
    selected_option: int
    selector: int | None = None
    result: int | None = None
    filename: str | None = None


@dataclass
class UpgradeFlowTrace:
    """Host trace for events whose firmware boundary is currently known."""

    selected_option: int = 0
    phase: UpgradePhase = UpgradePhase.SELECT
    events: list[UpgradeTraceEvent] | None = None

    def __post_init__(self) -> None:
        if not 0 <= self.selected_option < OPTION_COUNT:
            raise ValueError("selected_option must be one of five upgrade options")
        if self.events is None:
            self.events = []

    def select(self, option: int) -> UpgradeTraceEvent:
        """Record a host selection without claiming the physical key mapping."""
        if not 0 <= option < OPTION_COUNT:
            raise ValueError("upgrade option must be in the range 0..4")
        self.selected_option = option
        self.phase = UpgradePhase.SELECT
        return self._append("select")

    def request_confirm(self) -> UpgradeTraceEvent:
        """Record the confirmation boundary; no update operation is executed."""
        self.phase = UpgradePhase.CONFIRM_REQUEST
        return self._append("confirm-request")

    def record_selector(self, selector: int, *, result: int | None = None) -> UpgradeTraceEvent:
        """Record an indirect call value without assigning its kernel meaning."""
        selector &= 0xFFFFFFFF
        if selector in ENTRY_SELECTORS:
            kind = "entry-selector"
        elif selector in FAILURE_SELECTORS:
            kind = "failure-selector"
        else:
            kind = "unresolved-selector"
        return self._append(kind, selector=selector, result=result)

    def decrypt_error(self) -> UpgradeTraceEvent:
        """Record the observed decrypt-error boundary."""
        self.phase = UpgradePhase.DECRYPT_ERROR
        return self._append("decrypt-error")

    def search_complete(self, *, filename: str | None = None) -> UpgradeTraceEvent:
        """Record search completion without validating or flashing a file."""
        if filename is not None and not filename:
            raise ValueError("filename must be non-empty when supplied")
        self.phase = UpgradePhase.SEARCH_COMPLETE
        return self._append("search-complete", filename=filename)

    def _append(
        self,
        kind: str,
        *,
        selector: int | None = None,
        result: int | None = None,
        filename: str | None = None,
    ) -> UpgradeTraceEvent:
        event = UpgradeTraceEvent(
            kind=kind,
            phase=self.phase.value,
            selected_option=self.selected_option,
            selector=selector,
            result=None if result is None else result & 0xFFFFFFFF,
            filename=filename,
        )
        assert self.events is not None
        self.events.append(event)
        return event

    def trace(self) -> dict[str, Any]:
        """Return a JSON-safe trace with explicit unresolved boundaries."""
        assert self.events is not None
        return {
            "kind": "upgrade-flow-trace-only",
            "selected_option": self.selected_option,
            "phase": self.phase.value,
            "events": [asdict(event) for event in self.events],
            "provenance": {
                "screen": "real upgrade.sty five-option PicBox loop",
                "preflight": "upgrade.ap decrypt/search strings and call boundary",
                "fixed_rom": "not present; indirect selector meanings unresolved",
            },
            "forbidden_claims": ("flash", "reboot", "successful-update"),
        }
