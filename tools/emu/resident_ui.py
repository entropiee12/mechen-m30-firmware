"""Cautious labels for resident UI targets recovered from ``drv_ui.drv``.

These are extracted resident helpers, not a complete ROM or syscall model.
The table records only effects supported by the resident-segment Ghidra probe;
unknown addresses intentionally remain unclassified.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class ResidentUiTarget:
    pointer: int
    name: str
    evidence: str
    effects: tuple[str, ...]


RESIDENT_UI_TARGETS: dict[int, ResidentUiTarget] = {
    0xBFC09CE0: ResidentUiTarget(
        0xBFC09CE0,
        "ui_noop",
        "resident-segment-decompile",
        ("return",),
    ),
    0xBFC09C00: ResidentUiTarget(
        0xBFC09C00,
        "ui_syscall_wrapper",
        "resident-segment-decompile",
        ("trap", "write-status-byte-2"),
    ),
    # The B3 literal pool uses the odd pointer 0xbfc09c05.  MIPS16 clears
    # only bit 0 for the instruction address, so that call lands at the
    # second trap entry (0xbfc09c04), not at 0xbfc09c00.  The instruction
    # scan shows the same trap/status-wrapper family at both entries; keep
    # it explicit instead of pretending that ``pointer & ~1`` aliases the
    # first entry.
    0xBFC09C04: ResidentUiTarget(
        0xBFC09C04,
        "ui_syscall_wrapper_alt_entry",
        "resident-segment-instruction-scan",
        ("trap", "write-status-byte-2"),
    ),
    0xBFC0A8BC: ResidentUiTarget(
        0xBFC0A8BC,
        "ui_state_setter_10",
        "resident-segment-decompile",
        ("write-resident-state-plus-0x10",),
    ),
    0xBFC0A8C8: ResidentUiTarget(
        0xBFC0A8C8,
        "ui_state_setter_14",
        "resident-segment-decompile",
        ("write-resident-state-plus-0x14",),
    ),
}


def classify(pointer: int) -> ResidentUiTarget | None:
    """Classify an odd MIPS16 pointer without executing it."""

    return RESIDENT_UI_TARGETS.get(pointer & 0xFFFFFFFE)
