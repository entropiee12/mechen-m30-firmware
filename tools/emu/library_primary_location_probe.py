#!/usr/bin/env python3
"""Measure byte-level correlations in M30 MUSIC.LIB location records.

This probe intentionally reports observed invariants only.  It does not assign
semantic names to the unresolved 73-byte primary location blob and never
rewrites the input image.
"""

from __future__ import annotations

import hashlib
from pathlib import Path
import struct
import sys


HEADER_SIZE = 0x200
RECORD_SIZE = 0x100
PRIMARY_OFFSET = 0xA9
PRIMARY_SIZE = 0x49
SECONDARY_OFFSET = 0xF4
SECONDARY_SIZE = 0x08


def inspect_primary_location(data: bytes) -> dict[str, object]:
    """Return reproducible primary/secondary byte correlations."""
    if len(data) < HEADER_SIZE or len(data) % 0x200:
        raise ValueError("MUSIC.LIB image must be sector aligned and contain a header")

    record_count = struct.unpack_from("<H", data, 0x08)[0]
    required_size = HEADER_SIZE + record_count * RECORD_SIZE
    if len(data) < required_size:
        raise ValueError("MUSIC.LIB image is shorter than its record area")

    word6_matches = 0
    word8_matches = 0
    both_matches = 0
    neither_matches = 0
    exclusive_matches = 0
    companion_delta_non_32_aligned = 0
    companion_deltas: list[int] = []
    word10_values: set[int] = set()
    word11_values: set[int] = set()

    for index in range(record_count):
        record_start = HEADER_SIZE + index * RECORD_SIZE
        record = data[record_start : record_start + RECORD_SIZE]
        primary = record[PRIMARY_OFFSET : PRIMARY_OFFSET + PRIMARY_SIZE]
        secondary = record[SECONDARY_OFFSET : SECONDARY_OFFSET + SECONDARY_SIZE]
        if len(primary) != PRIMARY_SIZE or len(secondary) != SECONDARY_SIZE:
            raise ValueError(f"record {index} is truncated")

        cluster_no = struct.unpack_from("<I", secondary, 0)[0]
        word6 = struct.unpack_from("<I", primary, 0x18)[0]
        word8 = struct.unpack_from("<I", primary, 0x20)[0]
        word10_values.add(struct.unpack_from("<I", primary, 0x28)[0])
        word11_values.add(struct.unpack_from("<I", primary, 0x2C)[0])

        word6_match = word6 == cluster_no
        word8_match = word8 == cluster_no
        word6_matches += word6_match
        word8_matches += word8_match
        both_matches += word6_match and word8_match
        neither_matches += not word6_match and not word8_match
        if word6_match ^ word8_match:
            exclusive_matches += 1
            companion_offset = 0x1C if word6_match else 0x24
            companion = struct.unpack_from("<I", primary, companion_offset)[0]
            dir_entry = struct.unpack_from("<I", secondary, 4)[0]
            delta = (companion - dir_entry) & 0xFFFFFFFF
            companion_deltas.append(delta)
            companion_delta_non_32_aligned += delta % 0x20 != 0

    return {
        "bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        "record_count": record_count,
        "primary_bytes": PRIMARY_SIZE,
        "word6_cluster_matches": word6_matches,
        "word8_cluster_matches": word8_matches,
        "both_cluster_matches": both_matches,
        "neither_cluster_matches": neither_matches,
        "exclusive_cluster_matches": exclusive_matches,
        "companion_delta_non_32_aligned": companion_delta_non_32_aligned,
        "companion_delta_min": min(companion_deltas, default=None),
        "companion_delta_max": max(companion_deltas, default=None),
        "word10_values": tuple(sorted(word10_values)),
        "word11_values": tuple(sorted(word11_values)),
    }


def main(path: Path) -> None:
    summary = inspect_primary_location(path.read_bytes())
    print(
        "MUSIC.LIB PRIMARY LOCATION CORRELATION "
        f"records={summary['record_count']} "
        f"word+0x18-cluster={summary['word6_cluster_matches']} "
        f"word+0x20-cluster={summary['word8_cluster_matches']} "
        f"both={summary['both_cluster_matches']} "
        f"neither={summary['neither_cluster_matches']} "
        f"exclusive={summary['exclusive_cluster_matches']} "
        f"delta-not-0x20-aligned={summary['companion_delta_non_32_aligned']}"
    )
    print(
        "companion-delta-range="
        f"{summary['companion_delta_min']:#x}..{summary['companion_delta_max']:#x}"
    )
    print(f"word+0x28-values={summary['word10_values']}")
    print(f"word+0x2c-values={summary['word11_values']}")
    print(f"sha256={summary['sha256']}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: library_primary_location_probe.py MUSIC.LIB")
    main(Path(sys.argv[1]))
