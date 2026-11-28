#!/usr/bin/env python3
"""Compare the candidate MUSIC.LIB order with a device-built image."""

from __future__ import annotations

import hashlib
from pathlib import Path
import struct
import sys
from typing import Sequence


TOOLS_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS_ROOT / "sim"))

from library_index_sort import sort_record_indices


SECTOR_SIZE = 512
HEADER_SIZE = 0x200
RECORD_SIZE = 0x100
SECTIONS = (
    ("ALLSONG", 0x10),
    ("ALBUM", 0x2C),
    ("ARTIST", 0x48),
    ("GENRE", 0x64),
)


def _read_order(data: bytes, record_count: int, section_offset: int) -> tuple[int, ...]:
    table_sector = struct.unpack_from("<H", data, section_offset + 14 + 6)[0]
    table_offset = table_sector * SECTOR_SIZE
    table_end = table_offset + record_count * 2

    if table_end > len(data):
        raise ValueError("MUSIC.LIB order table is outside the image")

    return struct.unpack_from(f"<{record_count}H", data, table_offset)


def _inverse_rank(order: Sequence[int], record_count: int) -> tuple[int, ...]:
    rank = [0] * record_count

    for position, record_index in enumerate(order):
        if not 0 <= record_index < record_count:
            raise ValueError("MUSIC.LIB order table contains an invalid record index")

        rank[record_index] = position

    if len(set(order)) != record_count:
        raise ValueError("MUSIC.LIB order table is not a permutation")

    return tuple(rank)


def compare_candidate_orders(
    records: Sequence[bytes],
    actual_orders: dict[str, Sequence[int]],
) -> dict[str, dict[str, int | None]]:
    """Compare each candidate order using the device's preceding rank.

    Feeding the observed preceding rank into each pass isolates the remaining
    differences to that pass's text conversion, tie refinement, or primary
    comparator. The function does not claim that the candidate order is a
    firmware writer replacement.
    """
    record_count = len(records)
    previous_rank: tuple[int, ...] | None = None
    summaries: dict[str, dict[str, int | None]] = {}

    for section_name, _ in SECTIONS:
        actual_order = tuple(actual_orders[section_name])

        if len(actual_order) != record_count:
            raise ValueError(f"{section_name} order does not cover every record")

        candidate_order = sort_record_indices(
            records,
            section_name,
            previous_rank=previous_rank,
        )
        matching_entries = sum(
            actual_index == candidate_index
            for actual_index, candidate_index in zip(actual_order, candidate_order)
        )
        first_mismatch = next(
            (
                position
                for position, (actual_index, candidate_index) in enumerate(
                    zip(actual_order, candidate_order)
                )
                if actual_index != candidate_index
            ),
            None,
        )
        summaries[section_name] = {
            "matching_entries": matching_entries,
            "record_count": record_count,
            "first_mismatch": first_mismatch,
        }
        previous_rank = _inverse_rank(actual_order, record_count)

    return summaries


def inspect_library_order(
    data: bytes,
    expected_sha256: str | None = None,
) -> dict[str, object]:
    """Read and compare a complete device-built MUSIC.LIB image."""
    if len(data) < HEADER_SIZE or len(data) % SECTOR_SIZE:
        raise ValueError("MUSIC.LIB image must be sector-aligned and have a header")

    actual_sha256 = hashlib.sha256(data).hexdigest()
    if expected_sha256 is not None and actual_sha256 != expected_sha256:
        raise ValueError(
            f"MUSIC.LIB SHA-256 {actual_sha256} does not match {expected_sha256}"
        )

    record_count = struct.unpack_from("<H", data, 8)[0]
    record_end = HEADER_SIZE + record_count * RECORD_SIZE

    if record_end > len(data):
        raise ValueError("MUSIC.LIB record area is outside the image")

    records = tuple(
        data[offset : offset + RECORD_SIZE]
        for offset in range(HEADER_SIZE, record_end, RECORD_SIZE)
    )
    actual_orders = {
        name: _read_order(data, record_count, section_offset)
        for name, section_offset in SECTIONS
    }

    return {
        "bytes": len(data),
        "sha256": actual_sha256,
        "record_count": record_count,
        "sections": compare_candidate_orders(records, actual_orders),
    }


def main(path: Path, expected_sha256: str | None) -> None:
    summary = inspect_library_order(
        path.read_bytes(),
        expected_sha256=expected_sha256,
    )
    print(
        "MUSIC.LIB ORDER PROBE PASS "
        f"bytes={summary['bytes']} "
        f"records={summary['record_count']} "
        f"sha256={summary['sha256']}"
    )
    for section_name, section in summary["sections"].items():
        print(
            f"section={section_name} "
            f"matching={section['matching_entries']}/{section['record_count']} "
            f"first_mismatch={section['first_mismatch']}"
        )


if __name__ == "__main__":
    if len(sys.argv) not in (2, 3):
        raise SystemExit("usage: library_order_probe.py MUSIC.LIB [EXPECTED_SHA256| -]")

    expected_sha256 = None
    if len(sys.argv) == 3 and sys.argv[2] != "-":
        expected_sha256 = sys.argv[2]

    main(Path(sys.argv[1]), expected_sha256)
