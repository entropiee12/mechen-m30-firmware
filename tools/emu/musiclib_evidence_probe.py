#!/usr/bin/env python3
"""Verify an external MUSIC.LIB sample through the host SD/index model."""

from __future__ import annotations

import hashlib
from pathlib import Path
import struct
import sys

from library_file import (
    SECTOR_SIZE,
    find_library_record_index,
    interpret_library_section_index,
    read_library_layout,
    read_library_record,
    read_library_section_index,
)
from sd_block_device import SdCardBlockDevice


EXPECTED_PUBLIC_SAMPLE_SHA256 = (
    "cc3f476c8edb50ea8493b726d46ac82b139a6e87a86cc04c2cdbc30fd3fb8afe"
)
INDEXED_SECTIONS = ("ALBUM", "ARTIST", "GENRE")


def _summarize_record_links(
    data: bytes,
    record_count: int,
) -> tuple[tuple[dict[str, int], ...], dict[str, int]]:
    samples: list[dict[str, int]] = []
    previous_linear_matches = 0
    next_one_past_matches = 0
    out_of_range_values = 0

    for index in range(record_count):
        offset = 0x200 + index * 0x100 + 0xFC
        previous_index, next_index = struct.unpack_from("<HH", data, offset)

        if previous_index == (0xFFFF if index == 0 else index - 1):
            previous_linear_matches += 1

        if next_index == index + 1:
            next_one_past_matches += 1

        if previous_index >= record_count:
            out_of_range_values += 1

        if next_index >= record_count:
            out_of_range_values += 1

        if index == 0 or index == record_count - 1:
            samples.append(
                {
                    "index": index,
                    "previous_index": previous_index,
                    "next_index": next_index,
                }
            )

    return tuple(samples), {
        "previous_linear_matches": previous_linear_matches,
        "next_one_past_matches": next_one_past_matches,
        "out_of_range_values": out_of_range_values,
    }


def inspect_music_library(
    data: bytes,
    expected_sha256: str | None = EXPECTED_PUBLIC_SAMPLE_SHA256,
) -> dict[str, object]:
    """Validate a byte image and return its bounded index summary."""
    if len(data) % SECTOR_SIZE:
        raise ValueError("MUSIC.LIB image must contain complete 512-byte sectors")

    sha256 = hashlib.sha256(data).hexdigest()
    if expected_sha256 is not None and sha256 != expected_sha256:
        raise ValueError(
            f"MUSIC.LIB SHA-256 {sha256} does not match {expected_sha256}"
        )

    card = SdCardBlockDevice(sector_count=len(data) // SECTOR_SIZE)
    for sector in range(card.sector_count):
        start = sector * SECTOR_SIZE
        card.write_sector(sector, data[start : start + SECTOR_SIZE])

    layout = read_library_layout(card, 0)
    sample_indices = tuple(
        dict.fromkeys(
            index
            for index in (0, 1, layout.record_count - 1)
            if 0 <= index < layout.record_count
        )
    )
    location_key_samples: list[dict[str, int]] = []
    location_lookup_samples: list[dict[str, int | None]] = []
    for index in sample_indices:
        record = read_library_record(card, 0, index)
        location_key_samples.append(
            {
                "index": index,
                "cluster_no": record.cluster_no,
                "dir_entry": record.dir_entry,
            }
        )
        found_index = find_library_record_index(
            card,
            0,
            record.location_secondary,
        )
        location_lookup_samples.append(
            {
                "index": index,
                "found_index": found_index,
            }
        )

    record_link_samples, record_link_summary = _summarize_record_links(
        data,
        layout.record_count,
    )
    section_summaries: dict[str, object] = {}
    for section_name in INDEXED_SECTIONS:
        section_index = read_library_section_index(card, 0, section_name)
        spans = interpret_library_section_index(
            section_index,
            layout.record_count,
        )
        section_summaries[section_name] = {
            "node_counts": layout.section(section_name).node_counts,
            "span_count": len(spans),
            "record_count_per_level": tuple(
                sum(len(span.record_indices) for span in spans if span.level == level)
                for level in range(3)
            ),
        }

    return {
        "bytes": len(data),
        "sha256": sha256,
        "record_count": layout.record_count,
        "record_area_sectors": layout.record_area_sectors,
        "location_key_samples": tuple(location_key_samples),
        "location_lookup_samples": tuple(location_lookup_samples),
        "record_link_samples": record_link_samples,
        "record_link_summary": record_link_summary,
        "sections": section_summaries,
    }


def main(path: Path, expected_sha256: str | None) -> None:
    summary = inspect_music_library(
        path.read_bytes(),
        expected_sha256=expected_sha256,
    )
    print(
        "MUSIC.LIB EVIDENCE PASS "
        f"bytes={summary['bytes']} "
        f"records={summary['record_count']} "
        f"record_area_sectors={summary['record_area_sectors']} "
        f"sha256={summary['sha256']}"
    )
    print(f"location_key_samples={summary['location_key_samples']}")
    print(f"location_lookup_samples={summary['location_lookup_samples']}")
    print(f"record_link_samples={summary['record_link_samples']}")
    print(f"record_link_summary={summary['record_link_summary']}")
    for name, section in summary["sections"].items():
        print(
            f"section={name} "
            f"node_counts={section['node_counts']} "
            f"spans={section['span_count']} "
            f"records_per_level={section['record_count_per_level']}"
        )


if __name__ == "__main__":
    if len(sys.argv) not in (2, 3):
        raise SystemExit(
            "usage: musiclib_evidence_probe.py MUSIC.LIB [EXPECTED_SHA256|-]"
        )

    expected_sha256 = (
        EXPECTED_PUBLIC_SAMPLE_SHA256
        if len(sys.argv) == 2 or sys.argv[2] != "-"
        else None
    )
    if len(sys.argv) == 3 and sys.argv[2] != "-":
        expected_sha256 = sys.argv[2]

    main(Path(sys.argv[1]), expected_sha256)
