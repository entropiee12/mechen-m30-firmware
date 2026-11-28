"""Host block-device boundary for the recovered M30 SD transport."""

from __future__ import annotations

from dataclasses import dataclass, field


SECTOR_SIZE = 512


class CardNotPresentError(IOError):
    """Raised when a block transfer is attempted without an inserted card."""


@dataclass
class SdCardBlockDevice:
    """Deterministic 512-byte sector storage for future filesystem emulation."""

    sector_count: int
    present: bool = True
    storage: bytearray = field(init=False)

    def __post_init__(self) -> None:
        if self.sector_count <= 0:
            raise ValueError("sector_count must be positive")
        self.storage = bytearray(self.sector_count * SECTOR_SIZE)

    def insert(self) -> None:
        self.present = True

    def remove(self) -> None:
        self.present = False

    @property
    def capacity_bytes(self) -> int:
        """Return the card capacity using the recovered 512-byte unit."""
        return self.sector_count * SECTOR_SIZE

    def read_sector(self, sector: int) -> bytes:
        self._validate_transfer(sector)
        start = sector * SECTOR_SIZE
        return bytes(self.storage[start : start + SECTOR_SIZE])

    def write_sector(self, sector: int, payload: bytes) -> None:
        if len(payload) != SECTOR_SIZE:
            raise ValueError(f"SD transfers must be exactly {SECTOR_SIZE} bytes")
        self._validate_transfer(sector)
        start = sector * SECTOR_SIZE
        self.storage[start : start + SECTOR_SIZE] = payload

    def _validate_transfer(self, sector: int) -> None:
        if not self.present:
            raise CardNotPresentError("SD card is not present")
        if not 0 <= sector < self.sector_count:
            raise IndexError("SD sector is outside the card")
