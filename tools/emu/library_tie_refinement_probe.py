#!/usr/bin/env python3
"""Compare the recovered tie-refinement candidate with device index tables.

This is an order-only experiment. It never writes MUSIC.LIB or changes the
default emulator sorter. The refinement shape follows the recovered
FUN_40114d54 path: equal runs are re-read in bounded key slices and sorted
again until no run remains or the field is exhausted.
"""

from __future__ import annotations

import hashlib
from itertools import product
from pathlib import Path
import struct
import sys
from typing import Mapping, Sequence


TOOLS_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS_ROOT / "sim"))

from library_index_sort import (
    INDEX_TEXT_CONVERSION_REPLACE_CONTINUE_PREFIX,
    INDEX_TEXT_CONVERSION_STALE_TAIL,
    SECTION_SPECS,
    refine_sort_record_indices,
)


SECTOR_SIZE = 512
HEADER_SIZE = 0x200
RECORD_SIZE = 0x100
def _read_order(data: bytes, record_count: int, section_number: int) -> tuple[int, ...]:
    section_offset = 0x10 + section_number * 0x1C
    table_sector = struct.unpack_from("<H", data, section_offset + 20)[0]
    table_offset = table_sector * SECTOR_SIZE

    return struct.unpack_from(f"<{record_count}H", data, table_offset)


def _inverse_rank(order: Sequence[int], record_count: int) -> tuple[int, ...]:
    rank = [0] * record_count

    for position, record_index in enumerate(order):
        rank[record_index] = position

    return tuple(rank)


def compare_library(
    data: bytes,
    expected_sha256: str | None = None,
    conversion_mode: str = INDEX_TEXT_CONVERSION_STALE_TAIL,
) -> dict[str, object]:
    conversion_modes = {
        section_name: conversion_mode
        for section_name in SECTION_SPECS
    }
    summary = compare_library_section_modes(
        data,
        conversion_modes,
        expected_sha256,
    )
    summary["conversion_mode"] = conversion_mode

    return summary


def compare_library_section_modes(
    data: bytes,
    conversion_modes: Mapping[str, str],
    expected_sha256: str | None = None,
    *,
    candidate_rank_chaining: bool = False,
    rank_sources: Mapping[str, str] | None = None,
) -> dict[str, object]:
    actual_sha256 = hashlib.sha256(data).hexdigest()

    if expected_sha256 is not None and actual_sha256 != expected_sha256:
        raise ValueError(
            f"MUSIC.LIB SHA-256 {actual_sha256} does not match {expected_sha256}"
        )

    if set(conversion_modes) != set(SECTION_SPECS):
        raise ValueError("conversion modes must name all four MUSIC.LIB sections")

    transition_sections = ("ALBUM", "ARTIST", "GENRE")
    if rank_sources is not None:
        if candidate_rank_chaining:
            raise ValueError("rank sources cannot be combined with candidate rank chaining")

        if set(rank_sources) != set(transition_sections):
            raise ValueError("rank sources must name ALBUM, ARTIST, and GENRE")

        if any(source not in ("device", "candidate") for source in rank_sources.values()):
            raise ValueError("rank sources must be device or candidate")

    record_count = struct.unpack_from("<H", data, 8)[0]
    records = tuple(
        data[offset : offset + RECORD_SIZE]
        for offset in range(HEADER_SIZE, HEADER_SIZE + record_count * RECORD_SIZE, RECORD_SIZE)
    )
    sections: dict[str, dict[str, int | None]] = {}
    actual_orders: dict[str, tuple[int, ...]] = {}
    candidate_orders: dict[str, tuple[int, ...]] = {}
    rank_source_modes: dict[str, str] = {}
    previous_section_name: str | None = None

    for section_name, (section_number, _, _) in SECTION_SPECS.items():
        actual_order = _read_order(data, record_count, section_number)
        previous_rank = None
        if section_number:
            if previous_section_name is None:
                raise AssertionError("non-first MUSIC.LIB section has no predecessor")

            rank_source = (
                rank_sources[section_name]
                if rank_sources is not None
                else "candidate" if candidate_rank_chaining else "device"
            )
            previous_order = (
                candidate_orders[previous_section_name]
                if rank_source == "candidate"
                else actual_orders[previous_section_name]
            )
            previous_rank = _inverse_rank(previous_order, record_count)
            rank_source_modes[section_name] = rank_source

        candidate_order = refine_sort_record_indices(
            records,
            section_name,
            previous_rank=previous_rank,
            conversion_mode=conversion_modes[section_name],
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
        sections[section_name] = {
            "matching_entries": matching_entries,
            "record_count": record_count,
            "first_mismatch": first_mismatch,
        }
        actual_orders[section_name] = actual_order
        candidate_orders[section_name] = candidate_order
        previous_section_name = section_name

    return {
        "bytes": len(data),
        "sha256": actual_sha256,
        "record_count": record_count,
        "conversion_mode": "per-section",
        "conversion_modes": dict(conversion_modes),
        "rank_source": (
            "per-transition"
            if rank_sources is not None
            else "candidate-previous-section"
            if candidate_rank_chaining
            else "device-previous-section"
        ),
        "rank_sources": rank_source_modes,
        "sections": sections,
    }


def compare_library_conversion_matrix(
    data: bytes,
    expected_sha256: str | None = None,
    *,
    candidate_rank_chaining: bool = False,
) -> tuple[dict[str, object], ...]:
    modes = ("stale-tail", "stop-zero")
    section_names = tuple(SECTION_SPECS)
    results = []

    for mode_selection in product(modes, repeat=len(section_names)):
        conversion_modes = dict(zip(section_names, mode_selection))
        results.append(
            compare_library_section_modes(
                data,
                conversion_modes,
                expected_sha256,
                candidate_rank_chaining=candidate_rank_chaining,
            )
        )

    return tuple(results)


def compare_library_rank_source_matrix(
    data: bytes,
    expected_sha256: str | None = None,
) -> tuple[dict[str, object], ...]:
    conversion_modes = ("stale-tail", "stop-zero")
    transition_sections = ("ALBUM", "ARTIST", "GENRE")
    sources = ("device", "candidate")
    results = []

    for conversion_mode in conversion_modes:
        section_modes = {
            section_name: conversion_mode
            for section_name in SECTION_SPECS
        }
        for selected_sources in product(sources, repeat=len(transition_sections)):
            rank_sources = dict(zip(transition_sections, selected_sources))
            summary = compare_library_section_modes(
                data,
                section_modes,
                expected_sha256,
                rank_sources=rank_sources,
            )
            summary["uniform_conversion_mode"] = conversion_mode
            results.append(summary)

    return tuple(results)


def compare_library_replacement_matrix(
    data: bytes,
    expected_sha256: str | None = None,
) -> tuple[dict[str, object], ...]:
    results = []

    for replacement_byte in range(1, 0x26):
        conversion_mode = (
            f"{INDEX_TEXT_CONVERSION_REPLACE_CONTINUE_PREFIX}{replacement_byte:02x}"
        )
        results.append(compare_library(data, expected_sha256, conversion_mode))

    return tuple(results)


def main(
    path: Path,
    expected_sha256: str | None,
    conversion_mode: str,
) -> None:
    data = path.read_bytes()

    if conversion_mode == "rank-source-matrix":
        matrix = compare_library_rank_source_matrix(data, expected_sha256)
        record_count = struct.unpack_from("<H", data, 8)[0]
        print(
            "MUSIC.LIB RANK SOURCE MATRIX PASS "
            f"bytes={len(data)} records={record_count} "
            f"sha256={hashlib.sha256(data).hexdigest()} candidates={len(matrix)}"
        )

        for summary in matrix:
            rank_sources = summary["rank_sources"]
            sections = summary["sections"]
            rank_label = ",".join(
                f"{section_name}={rank_sources[section_name]}"
                for section_name in ("ALBUM", "ARTIST", "GENRE")
            )
            scores = ",".join(
                f"{section_name}={sections[section_name]['matching_entries']}"
                for section_name in SECTION_SPECS
            )
            print(
                f"conversion={summary['uniform_conversion_mode']} "
                f"ranks={rank_label} matches={scores}"
            )

        return

    if conversion_mode == "replace-matrix":
        matrix = compare_library_replacement_matrix(data, expected_sha256)
        record_count = struct.unpack_from("<H", data, 8)[0]
        print(
            "MUSIC.LIB REPLACEMENT BYTE MATRIX PASS "
            f"bytes={len(data)} records={record_count} "
            f"sha256={hashlib.sha256(data).hexdigest()} candidates={len(matrix)}"
        )

        for summary in matrix:
            sections = summary["sections"]
            scores = ",".join(
                f"{section_name}={sections[section_name]['matching_entries']}"
                for section_name in SECTION_SPECS
            )
            replacement_byte = summary["conversion_mode"].split(":", 1)[1]
            print(f"replacement=0x{replacement_byte} matches={scores}")

        return

    if conversion_mode in ("matrix", "chain-matrix"):
        candidate_rank_chaining = conversion_mode == "chain-matrix"
        matrix = compare_library_conversion_matrix(
            data,
            expected_sha256,
            candidate_rank_chaining=candidate_rank_chaining,
        )
        record_count = struct.unpack_from("<H", data, 8)[0]
        print(
            "MUSIC.LIB CONVERSION MATRIX PASS "
            f"bytes={len(data)} records={record_count} "
            f"sha256={hashlib.sha256(data).hexdigest()} candidates={len(matrix)} "
            f"rank_source={matrix[0]['rank_source']}"
        )

        for summary in matrix:
            modes = summary["conversion_modes"]
            sections = summary["sections"]
            mode_label = ",".join(
                f"{section_name}={modes[section_name]}"
                for section_name in SECTION_SPECS
            )
            scores = ",".join(
                f"{section_name}={sections[section_name]['matching_entries']}"
                for section_name in SECTION_SPECS
            )
            print(f"modes={mode_label} matches={scores}")

        return

    summary = compare_library(
        data,
        expected_sha256,
        conversion_mode,
    )
    print(
        "MUSIC.LIB TIE REFINEMENT PROBE PASS "
        f"bytes={summary['bytes']} records={summary['record_count']} "
        f"sha256={summary['sha256']} conversion={summary['conversion_mode']}"
    )

    for section_name, section in summary["sections"].items():
        print(
            f"section={section_name} "
            f"matching={section['matching_entries']}/{section['record_count']} "
            f"first_mismatch={section['first_mismatch']}"
        )


if __name__ == "__main__":
    if len(sys.argv) not in (2, 3, 4):
        raise SystemExit(
            "usage: library_tie_refinement_probe.py MUSIC.LIB "
            "[EXPECTED_SHA256| -] "
            "[stale-tail|stop-zero|matrix|chain-matrix|rank-source-matrix|replace-matrix]"
        )

    expected_sha256 = None
    if len(sys.argv) >= 3 and sys.argv[2] != "-":
        expected_sha256 = sys.argv[2]

    conversion_mode = INDEX_TEXT_CONVERSION_STALE_TAIL
    if len(sys.argv) == 4:
        conversion_mode = sys.argv[3]

    main(Path(sys.argv[1]), expected_sha256, conversion_mode)
