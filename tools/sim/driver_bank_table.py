"""Decode the unit bank tables of Actions ``yqhx`` drivers.

Evidence (read-only static analysis, 2026-11-27):

* ``aud_dev.drv`` header ``0x4c`` is ``0x500`` and its ``0x100`` table is
  ``3000 6003 8009 1011 8012 601a 4020 4024``.  Entry 5 decodes to file
  ``0x1f00`` with size ``0x600``, which is the bank whose MIPS16 code the
  ``RunAuddevIdx18*`` p-code probes execute at window ``0x9400``.
* ``kernel.drv`` has two tables.  Within each table every entry starts where
  the previous one ends, and the last bank of the ``0x100`` table ends exactly
  at the end of the file (``0x4c00``).

Table format: little-endian ``u16`` entries, upper nibble is the bank size in
``0x100``-byte units, lower 12 bits are the start offset in the same units.
The table named ``A`` lives at header ``0x80`` with its base in header
``0x48`` and executes in window ``0x9000``.  The table named ``B`` lives at
header ``0x100`` with its base in header ``0x4c`` and executes in window
``0x9400``.

Far pointers carry the group in the high nibble of their top byte (``0x1x`` is
A, ``0x2x`` is B), the bank index in ``byte2 / 4`` and the window address in
the low 16 bits.  Bit 0 of the window address is the MIPS16 tag.  The meaning
of the low nibble of the top byte (``0xc`` in ``aud_dev``, ``0xf`` in
``kernel.drv``) is not established and is ignored.

``tools/emu/driver_banks.py`` reads these tables as big-endian four-byte
``(start, end)`` pairs.  That reading does not match the contiguity evidence
above for ``kernel.drv`` and ``aud_dev.drv``; this module does not replace it.
"""

from __future__ import annotations

from dataclasses import dataclass
import struct


UNIT_BYTES = 0x100

GROUP_A = "A"
GROUP_B = "B"

_GROUP_LAYOUT = {
    GROUP_A: {"base_field": 0x48, "table_offset": 0x80, "window": 0x9000},
    GROUP_B: {"base_field": 0x4C, "table_offset": 0x100, "window": 0x9400},
}
_POINTER_GROUPS = {0x1: GROUP_A, 0x2: GROUP_B}
_MAX_BANKS = 64


@dataclass(frozen=True)
class UnitBank:
    group: str
    index: int
    file_start: int
    size: int
    window: int

    @property
    def file_end(self) -> int:
        return self.file_start + self.size


@dataclass(frozen=True)
class FarTarget:
    pointer: int
    group: str
    bank_index: int
    window_address: int
    mips16: bool
    bank: UnitBank | None
    file_offset: int | None


class UnitBankImage:
    """Bank tables of one driver image, without executing any bank."""

    def __init__(self, data: bytes) -> None:
        if len(data) < 0x200 or data[4:8] != b"yqhx":
            raise ValueError("not a yqhx driver image")

        self._data = data
        self._banks: dict[str, tuple[UnitBank, ...]] = {
            group: self._decode_group(group)
            for group in (GROUP_A, GROUP_B)
        }

    def banks(self, group: str) -> tuple[UnitBank, ...]:
        return self._banks[group]

    def bank(self, group: str, index: int) -> UnitBank:
        banks = self._banks[group]
        if not 0 <= index < len(banks):
            raise IndexError(f"bank {group}{index} is not in the table")

        return banks[index]

    def resolve(self, pointer: int) -> FarTarget:
        """Resolve a far pointer to its bank and file offset.

        Returns ``bank=None`` and ``file_offset=None`` when the group or bank
        index is not in the tables or the window address is outside the bank.
        """
        if not 0 <= pointer <= 0xFFFFFFFF:
            raise ValueError("pointer must be an unsigned 32-bit integer")

        group = _POINTER_GROUPS.get((pointer >> 28) & 0xF)
        if group is None:
            raise ValueError(f"pointer 0x{pointer:08x} has no bank group")

        bank_index = ((pointer >> 16) & 0xFF) >> 2
        window_address = pointer & 0xFFFF
        banks = self._banks[group]
        bank = banks[bank_index] if bank_index < len(banks) else None
        file_offset = None
        if bank is not None:
            relative = (window_address & ~1) - bank.window
            if 0 <= relative < bank.size:
                file_offset = bank.file_start + relative

        if file_offset is None:
            bank = None

        return FarTarget(
            pointer=pointer,
            group=group,
            bank_index=bank_index,
            window_address=window_address,
            mips16=bool(window_address & 1),
            bank=bank,
            file_offset=file_offset,
        )

    def _decode_group(self, group: str) -> tuple[UnitBank, ...]:
        layout = _GROUP_LAYOUT[group]
        base = struct.unpack_from("<I", self._data, layout["base_field"])[0]
        table = layout["table_offset"]
        banks: list[UnitBank] = []
        expected_start: int | None = None
        for index in range(_MAX_BANKS):
            offset = table + index * 2
            if offset + 2 > len(self._data):
                break

            entry = struct.unpack_from("<H", self._data, offset)[0]
            if entry == 0:
                break

            count = entry >> 12
            start = entry & 0xFFF
            if count == 0:
                raise ValueError(f"bank {group}{index} has size zero")

            if expected_start is not None and start != expected_start:
                raise ValueError(
                    f"bank {group}{index} starts at unit 0x{start:x}, "
                    f"expected 0x{expected_start:x}"
                )

            file_start = base + start * UNIT_BYTES
            size = count * UNIT_BYTES
            if file_start + size > len(self._data):
                raise ValueError(f"bank {group}{index} exceeds the driver image")

            banks.append(UnitBank(group, index, file_start, size, layout["window"]))
            expected_start = start + count

        return tuple(banks)
