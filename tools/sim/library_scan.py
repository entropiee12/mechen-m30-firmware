"""Host model of the stock library scan's fixed record capacity."""

from __future__ import annotations

from dataclasses import dataclass


MAX_LIBRARY_RECORDS = 4_000


@dataclass(frozen=True)
class LibraryScanPlan:
    """The accepted portion of one scan, without filesystem side effects."""

    candidate_records: int
    accepted_records: int
    stopped_at_capacity: bool


def plan_library_scan(
    candidate_records: int,
    *,
    capacity: int = MAX_LIBRARY_RECORDS,
) -> LibraryScanPlan:
    """Apply the recovered fixed-array scan limit to candidate records."""
    if candidate_records < 0:
        raise ValueError("candidate_records must be non-negative")
    if capacity <= 0:
        raise ValueError("capacity must be positive")

    accepted_records = min(candidate_records, capacity)
    return LibraryScanPlan(
        candidate_records=candidate_records,
        accepted_records=accepted_records,
        stopped_at_capacity=candidate_records > capacity,
    )
