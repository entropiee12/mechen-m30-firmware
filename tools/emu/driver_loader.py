#!/usr/bin/env python3
"""Host-side materializer for the verified ``yqhx`` file-format boundary.

This turns the header-derived hypotheses into deterministic events. It models
copying and residency bookkeeping only; no raw MIPS payload is called. The
``a408``/``a578`` variants are synthetic slot-layout cases and are not a claim
that the similarly named M30 kernel helpers implement these operations.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from yqhx import YqhxImage, slot_layout


@dataclass(frozen=True)
class LoaderSpec:
    name: str
    header_read: int
    slot_stride: int
    uses_entry_callback: bool


LOADER_A408 = LoaderSpec("bfc0a408", 0x4C, 0, True)
LOADER_A578 = LoaderSpec("bfc0a578", 0x44, 12, False)


@dataclass(frozen=True)
class DriverLoadEvent:
    loader: str
    mode: int
    slot_offset: int
    header_read: int
    already_installed: bool
    plan: tuple[tuple[str, int, int], ...]
    entry: int
    entry_context: bytes | None


@dataclass
class ResidentMemory:
    """Sparse destination memory for the verified ``yqhx`` copy contract."""

    _data: dict[int, int] = field(default_factory=dict)

    def read(self, address: int, size: int) -> bytes:
        if address < 0 or size < 0:
            raise ValueError("memory address and size must be non-negative")

        return bytes(
            self._data.get(address + offset, 0)
            for offset in range(size)
        )

    def load(self, address: int, data: bytes) -> None:
        if address < 0:
            raise ValueError("memory address must be non-negative")

        for offset, value in enumerate(data):
            self._data[address + offset] = value

    def write(self, address: int, data: bytes) -> None:
        self.load(address, data)

    def apply(self, image: YqhxImage) -> None:
        """Copy image segments and clear the image's BSS without executing it."""
        source = image.path.read_bytes()

        for segment in image.segments:
            if segment.length == 0:
                continue

            payload = source[segment.file_offset : segment.file_offset + segment.length]
            if len(payload) != segment.length:
                raise ValueError("yqhx segment source is shorter than its declared length")

            self.load(segment.destination, payload)

        if image.bss_size:
            self.load(image.bss_address, bytes(image.bss_size))


class ResidentDriverLoader:
    """Track the resident slots exposed by one decompiled loader."""

    def __init__(self, spec: LoaderSpec):
        self.spec = spec
        self._installed: set[int] = set()
        self.events: list[DriverLoadEvent] = []

    @classmethod
    def a408(cls) -> "ResidentDriverLoader":
        return cls(LOADER_A408)

    @classmethod
    def a578(cls) -> "ResidentDriverLoader":
        return cls(LOADER_A578)

    def slot_offset(self, mode: int) -> int:
        if self.spec is LOADER_A408:
            return (mode & 0xFF) * slot_layout(mode)[1]
        return (mode & 0xFF) * self.spec.slot_stride

    def load(
        self,
        path: Path,
        mode: int,
        *,
        memory: ResidentMemory | None = None,
    ) -> DriverLoadEvent:
        image = YqhxImage.load(path)
        offset = self.slot_offset(mode)
        already_installed = offset in self._installed
        if not already_installed:
            self._installed.add(offset)

        if memory is not None:
            memory.apply(image)

        event = DriverLoadEvent(
            loader=self.spec.name,
            mode=mode & 0xFF,
            slot_offset=offset,
            header_read=self.spec.header_read,
            already_installed=already_installed,
            plan=image.load_plan(),
            entry=image.entry,
            entry_context=image.entry_context if self.spec.uses_entry_callback else None,
        )
        self.events.append(event)
        return event
