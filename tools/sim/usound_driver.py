"""Inference-labeled operation-table lookup for the extracted USB Audio driver."""

from __future__ import annotations

from dataclasses import dataclass


USOUND_DRIVER_MODULE_ID = 5
USOUND_OPERATION_COUNT = 8


@dataclass(frozen=True)
class OperationEntryCandidate:
    operation: int
    raw_pointer: int
    code_address: int
    instruction_set: str
    dispatch_basis: str = "cross-driver table[op] inference"


def resolve_operation_candidate(
    operation_table: tuple[int, ...],
    operation: int,
) -> OperationEntryCandidate | None:
    """Resolve a candidate entry without claiming the missing ROM dispatch."""

    if len(operation_table) != USOUND_OPERATION_COUNT:
        raise ValueError("usound candidate operation table must contain eight words")

    if not 0 <= operation < USOUND_OPERATION_COUNT:
        return None

    raw_pointer = operation_table[operation] & 0xFFFFFFFF
    if raw_pointer == 0:
        return None

    instruction_set = "MIPS16" if raw_pointer & 1 else "MIPS32"
    return OperationEntryCandidate(
        operation=operation,
        raw_pointer=raw_pointer,
        code_address=raw_pointer & ~1,
        instruction_set=instruction_set,
    )
