"""Evidence-backed FAT32/exFAT extension-table boundary.

The resident FAT32 and exFAT drivers share a fixed 31-slot table. Their
matcher checks the caller mask from bit 31 down to bit 1: slot 0 uses bit 31
and slot 30 uses bit 1. Bit 0 is not consumed by the recovered loop.
"""

from __future__ import annotations

from collections.abc import Collection
from dataclasses import dataclass


FIRMWARE_EXTENSION_SLOTS: tuple[str, ...] = (
    "MP3",
    "WMA",
    "WAV",
    "AAC",
    "FLAC",
    "APE",
    "AVI",
    "TXT",
    "JPG",
    "JPEG",
    "DSF",
    "DFF",
    "LRC",
    "ASF",
    "OGG",
    "TTA",
    "ISO",
    "AAX",
    "AA",
    "MP1",
    "MP2",
    "M4A",
    "POS",
    "ACT",
    "BMK",
    "AIF",
    "APK",
    "PCM",
    "**",
    "*f",
    "   ",
)


MUSIC_LIBRARY_EXTENSION_MASK = 0x7E190EA0
M3U_LIBRARY_EXTENSION_MASK = 0x00080400


def driver_slot_positions(mask: int) -> tuple[int, ...]:
    """Return slots selected by the resident driver's high-bit-first loop."""
    if not 0 <= mask <= 0xFFFFFFFF:
        raise ValueError("filesystem extension mask must be an unsigned 32-bit value")

    return tuple(
        slot
        for slot in range(len(FIRMWARE_EXTENSION_SLOTS))
        if mask & (1 << (31 - slot))
    )


@dataclass(frozen=True)
class FilesystemExtensionTable:
    """Resolve the proven slot vocabulary and explicit caller selections."""

    slots: tuple[str, ...] = FIRMWARE_EXTENSION_SLOTS

    def __post_init__(self) -> None:
        if len(self.slots) != 31:
            raise ValueError("firmware extension table must contain 31 slots")

        if any(len(slot.encode("ascii")) > 4 for slot in self.slots):
            raise ValueError("firmware extension slots must fit four bytes")

    def slot_for_extension(self, extension: str) -> int | None:
        """Return the exact firmware slot for an ASCII extension, if present."""
        candidate = extension.upper()

        try:
            normalized_slots = tuple(slot.upper() for slot in self.slots)
            return normalized_slots.index(candidate)

        except ValueError:
            return None

    def accepts_slot(self, extension: str, accepted_slots: Collection[int]) -> bool:
        """Apply an explicit caller decision without modeling its bitmask."""
        slot = self.slot_for_extension(extension)

        if slot is None:
            return False

        return slot in accepted_slots


@dataclass(frozen=True)
class LibraryExtensionMask:
    """A raw playlist mask using the resident driver's bit-to-slot order."""

    name: str
    value: int

    @property
    def enabled_bit_positions(self) -> tuple[int, ...]:
        return tuple(index for index in range(32) if self.value & (1 << index))

    @property
    def driver_slot_positions(self) -> tuple[int, ...]:
        """Return extension slots selected by the recovered matcher."""
        return driver_slot_positions(self.value)


MUSIC_LIBRARY_MASK = LibraryExtensionMask("MUSIC.LIB", MUSIC_LIBRARY_EXTENSION_MASK)
M3U_LIBRARY_MASK = LibraryExtensionMask("M3U.LIB", M3U_LIBRARY_EXTENSION_MASK)
