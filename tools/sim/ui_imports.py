"""Reference-only names for public ATJ2127 UI operation ordinals.

The public SDK's ``ui_driver_operations`` table places these operations at
the same ordinals seen in the M30 B3 delay slots.  This is useful ABI evidence,
not proof that the M30 resident dispatcher has identical semantics.  Callers
must keep treating the selector as unresolved until an M30 dispatcher is
recovered.
"""

from __future__ import annotations


PUBLIC_UI_COMMANDS: dict[int, str] = {
    15: "UI_SCROLLSTRING_EXT",
    17: "UI_GET_DC_STATUS",
}


def public_ui_command(selector: int) -> str | None:
    """Return the public-SDK comparison name, without asserting M30 meaning."""

    return PUBLIC_UI_COMMANDS.get(selector)
