"""Actions bank-qualified address decoding for host-side probes.

This is an address contract, not a bank loader.  It deliberately distinguishes
the active A/B/C overlay window from the low-offset entry/stub area used by
indirect calls.  A future harness can attach the real lazy-load behavior
without accidentally treating a stub pointer as already resident code.
"""

from __future__ import annotations

from dataclasses import dataclass


BANK_SPACE = 0x40000
BANK_OFFSETS = {"A": 0x24C00, "B": 0x25000, "C": 0x21A00}
BANK_SIZES = {"A": 0x400, "B": 0x800, "C": 0x400}
TYPE_NAMES = {1: "A", 2: "B", 3: "C"}


@dataclass(frozen=True)
class BankAddress:
    value: int
    group: int | None
    bank_type: str | None
    bank_index: int | None
    low_offset: int | None
    classification: str
    window_start: int | None = None
    payload_offset: int | None = None

    @property
    def is_overlay_window(self) -> bool:
        return self.classification in {"overlay", "overlay-lead-in"}

    @property
    def entry_offset(self) -> int | None:
        """Return the aligned MIPS16 entry offset for a far candidate."""

        if self.payload_offset is None:
            return None
        return self.payload_offset & ~1

    @property
    def candidate_runtime_entry(self) -> int | None:
        """Return the public-formula runtime address for a validated candidate.

        This is an address-model result, not a hardware observation. Callers
        must still require an explicit entry validation before executing it.
        """

        if self.window_start is None or self.entry_offset is None:
            return None
        return self.window_start + self.entry_offset


def decode(address: int, *, ui_group: int = 6, lead_in_bytes: int = 0x0C) -> BankAddress:
    """Classify an Actions runtime pointer without dereferencing it."""

    address &= 0xFFFFFFFF
    type_code = (address >> 28) & 0xF
    group = (address >> 24) & 0xF
    bank_type = TYPE_NAMES.get(type_code)
    if bank_type is None or group != ui_group:
        return BankAddress(address, None, None, None, None, "resident-or-external")

    bank_index = (address >> 18) & 0x3F
    low_offset = address & 0x3FFFF
    window_offset = BANK_OFFSETS[bank_type]
    window_size = BANK_SIZES[bank_type]
    window_start = (
        (type_code << 28)
        | (group << 24)
        | (bank_index << 18)
        | window_offset
    )
    # The M30 UI far entries observed in the B3 literal pool use a second
    # logical address range beginning at 0x9000.  Subtracting that prefix
    # yields an offset inside the actual A/B/C overlay window.  This is a
    # loader candidate, not a proof of the hardware translation rule; callers
    # must keep it separate from an already-resident overlay address.
    payload_offset = None
    if 0x9000 <= low_offset < 0x9000 + window_size:
        payload_offset = low_offset - 0x9000
    if window_offset - lead_in_bytes <= low_offset < window_offset:
        classification = "overlay-lead-in"
    elif window_offset <= low_offset < window_offset + window_size:
        classification = "overlay"
    else:
        classification = "bank-stub-or-resident"
    return BankAddress(
        address,
        group,
        bank_type,
        bank_index,
        low_offset,
        classification,
        window_start,
        payload_offset,
    )
