#!/usr/bin/env python3
"""Conservative lazy-bank runtime for the host-side M30 emulator."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from driver_banks import DriverBankImage, FarEntryCandidate


# ``ker_xsys`` proves the exception-vector handoff, but the fixed handler is
# outside the extracted image. Keep the address as trace metadata only; never
# use it as a callable host function pointer.
EXCEPTION_HANDLER_TARGET = 0xBFC06D6C


@dataclass(frozen=True)
class BankLoad:
    pointer: int
    group: str
    index: int
    payload_offset: int
    entry_offset: int
    file_offset: int
    entry_file_offset: int


@dataclass(frozen=True)
class DispatchResult:
    pointer: int
    status: str
    dispatch_policy: str
    candidate: FarEntryCandidate | None
    loaded: bool


class LazyBankRuntime:
    """Model the observable part of a far call without executing raw bytes."""

    def __init__(
        self,
        driver: DriverBankImage,
        *,
        validated_entries: set[tuple[str, int, int]] | None = None,
    ) -> None:
        self.driver = driver
        self.validated_entries = validated_entries or set()
        self.active: tuple[str, int] | None = None
        self.loads: list[BankLoad] = []

    @classmethod
    def from_path(
        cls,
        path: Path,
        *,
        validated_entries: set[tuple[str, int, int]] | None = None,
    ) -> "LazyBankRuntime":
        return cls(DriverBankImage.load(path), validated_entries=validated_entries)

    def dispatch(self, pointer: int) -> DispatchResult:
        candidate = self.driver.resolve_far_candidate(pointer)
        if candidate is None:
            return DispatchResult(
                pointer & 0xFFFFFFFF,
                "resident-or-unresolved",
                "resident-or-unresolved",
                None,
                False,
            )
        if not candidate.bank_present:
            return DispatchResult(
                candidate.pointer,
                "bank-missing-or-external",
                "lazy-only",
                candidate,
                False,
            )

        # Validation is keyed by the actual aligned MIPS16 entry, while the
        # candidate keeps the raw odd pointer offset for provenance.
        key = (candidate.group, candidate.index, candidate.entry_offset)
        loaded = self.active != (candidate.group, candidate.index)
        if loaded:
            self.active = (candidate.group, candidate.index)
            self.loads.append(
                BankLoad(
                    candidate.pointer,
                    candidate.group,
                    candidate.index,
                    candidate.payload_offset,
                    candidate.entry_offset,
                    candidate.file_offset,
                    candidate.entry_file_offset,
                )
            )

        if key not in self.validated_entries:
            return DispatchResult(
                candidate.pointer,
                "bank-loaded-entry-unresolved",
                "never-direct-call",
                candidate,
                loaded,
            )
        return DispatchResult(
            candidate.pointer,
            "validated-entry",
            "lazy-load-then-call",
            candidate,
            loaded,
        )

    def boundary_trace(self, pointer: int) -> tuple[dict[str, object], ...]:
        """Return the conservative exception/load/retry boundary trace.

        The extracted ``ker_xsys`` image proves an exception handoff, while
        the YQHX header/materializer provides the file-copy contract; the
        fixed handler and actual bank-copy path that join those pieces are not
        in the extracted files. This method therefore models the observable
        envelope without executing any raw pointer. An unvalidated entry
        stops after the load, which is the important fail-closed behavior for
        the emulator.
        """
        result = self.dispatch(pointer)
        if result.candidate is None:
            return ({"kind": "resident-or-unresolved", "pointer": result.pointer},)
        candidate = result.candidate
        if not candidate.bank_present:
            return (
                {
                    "kind": "provider-missing",
                    "pointer": candidate.pointer,
                    "group": candidate.group,
                    "index": candidate.index,
                },
            )

        events: list[dict[str, object]] = []
        if result.loaded:
            events.extend(
                (
                    {
                        "kind": "overlay-exception",
                        "pointer": candidate.pointer,
                        "reason": "bank-not-resident",
                        "handler_target": EXCEPTION_HANDLER_TARGET,
                    },
                    {
                        "kind": "bank-load",
                        "group": candidate.group,
                        "index": candidate.index,
                        "payload_offset": candidate.payload_offset,
                    },
                )
            )
        if result.status == "validated-entry":
            events.append(
                {
                    "kind": "retry-entry" if result.loaded else "resident-entry",
                    "group": candidate.group,
                    "index": candidate.index,
                    "entry_offset": candidate.entry_offset,
                }
            )
        else:
            events.append(
                {
                    "kind": "stop-unresolved-entry",
                    "group": candidate.group,
                    "index": candidate.index,
                    "entry_offset": candidate.entry_offset,
                }
            )
        return tuple(events)
