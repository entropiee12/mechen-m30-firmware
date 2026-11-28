#!/usr/bin/env python3
"""Host-side model of the M30 ``drv_ui.drv`` bank table.

The table parser is intentionally separate from code execution. A far
pointer can identify a group/bank and a *candidate* payload offset, but that
does not prove that the bytes at that offset are an entry point.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass
from pathlib import Path


GROUPS = (("A", 0x48, 0x80), ("B", 0x4C, 0x100), ("C", 0x50, 0x180))
TYPE_CODES = {"A": 1, "B": 2, "C": 3}
TYPE_NAMES = {value: key for key, value in TYPE_CODES.items()}
BANK_SIZES = {"A": 0x400, "B": 0x800, "C": 0x400}


@dataclass(frozen=True)
class DriverBank:
    group: str
    index: int
    file_start: int
    file_end: int
    payload: bytes


@dataclass(frozen=True)
class FarEntryCandidate:
    pointer: int
    group: str
    index: int
    payload_offset: int
    file_offset: int | None
    payload: bytes
    bank_present: bool

    @property
    def entry_offset(self) -> int:
        """Return the aligned MIPS16 code offset for the far pointer.

        Actions far-call pointers carry the MIPS16 mode bit in bit 0.  Keep
        ``payload_offset`` lossless for provenance, but do not make callers
        rediscover that the code address is one byte earlier for odd entries.
        """

        return self.payload_offset & ~1

    @property
    def entry_file_offset(self) -> int | None:
        """Return the aligned file offset when the bank is present."""

        if self.file_offset is None:
            return None
        return self.file_offset - (self.payload_offset - self.entry_offset)


class DriverBankImage:
    """Parse a driver without pretending to execute its overlays."""

    def __init__(self, path: Path, data: bytes, banks: tuple[DriverBank, ...]):
        self.path = path
        self.data = data
        self.banks = banks
        self._by_key = {(bank.group, bank.index): bank for bank in banks}

    @classmethod
    def load(cls, path: Path) -> "DriverBankImage":
        data = path.read_bytes()
        if data[4:8] != b"yqhx":
            raise ValueError(f"not an Actions driver: {path}")
        banks: list[DriverBank] = []
        for group, group_field, table_offset in GROUPS:
            group_start = struct.unpack_from("<I", data, group_field)[0]
            for index in range(64):
                entry = table_offset + index * 4
                if entry + 4 > len(data):
                    break
                start, end = struct.unpack_from(">HH", data, entry)
                if start == 0 and end == 0:
                    break
                if end <= start:
                    raise ValueError(
                        f"invalid {group}{index} range: 0x{start:x}..0x{end:x}"
                    )
                file_start = group_start + start
                file_end = group_start + end
                if file_end > len(data):
                    raise ValueError(f"{group}{index} exceeds driver size")
                banks.append(
                    DriverBank(group, index, file_start, file_end, data[file_start:file_end])
                )
        if not banks:
            raise ValueError(f"no overlay banks in {path}")
        return cls(path, data, tuple(banks))

    def bank(self, group: str, index: int) -> DriverBank:
        try:
            return self._by_key[(group, index)]
        except KeyError as exc:
            raise KeyError(f"driver has no {group}{index:02d} bank") from exc

    def resolve_far_candidate(self, pointer: int, *, ui_group: int = 6) -> FarEntryCandidate | None:
        """Map the observed ``0x9000 + payload_offset`` convention.

        The return value is explicitly named *candidate*: the loader rule is
        not yet proven on hardware and the payload may contain data at the
        resulting offset.
        """

        pointer &= 0xFFFFFFFF
        type_code = (pointer >> 28) & 0xF
        group = (pointer >> 24) & 0xF
        bank_type = TYPE_NAMES.get(type_code)
        if bank_type is None or group != ui_group:
            return None
        index = (pointer >> 18) & 0x3F
        low_offset = pointer & 0x3FFFF
        payload_offset = low_offset - 0x9000
        if not 0 <= payload_offset < BANK_SIZES[bank_type]:
            return None
        bank = self._by_key.get((bank_type, index))
        if bank is None:
            return FarEntryCandidate(
                pointer,
                bank_type,
                index,
                payload_offset,
                None,
                b"",
                False,
            )
        if payload_offset >= len(bank.payload):
            return None
        return FarEntryCandidate(
            pointer,
            bank_type,
            index,
            payload_offset,
            bank.file_start + payload_offset,
            bank.payload[payload_offset:],
            True,
        )
