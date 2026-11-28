#!/usr/bin/env python3
"""Correlate MUSIC.LIB file keys with a read-only mounted music tree.

This is a path-level cross-check, not a raw exFAT decoder.  It only uses file
names exposed by the mount and never writes to the volume.
"""

from __future__ import annotations

from collections import defaultdict
from pathlib import Path
import struct
import sys

TOOLS_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS_ROOT / "sim"))

from library_record_codec import decode_record


HEADER_SIZE = 0x200
RECORD_SIZE = 0x100


def inspect_mounted_library(data: bytes, music_root: Path) -> dict[str, int]:
    """Return bounded filename/cluster grouping statistics."""
    if len(data) < HEADER_SIZE:
        raise ValueError("MUSIC.LIB image is shorter than its header")
    record_count = struct.unpack_from("<H", data, 0x08)[0]
    required = HEADER_SIZE + record_count * RECORD_SIZE
    if len(data) < required:
        raise ValueError("MUSIC.LIB image is shorter than its record area")
    if not music_root.is_dir():
        raise ValueError("music root is not a directory")

    files_by_name: dict[str, list[Path]] = defaultdict(list)
    for path in music_root.rglob("*"):
        if path.is_file() and not path.name.startswith("._"):
            files_by_name[path.name].append(path)

    matched_records = 0
    unique_name_matches = 0
    primary_grandparent_cluster_matches = 0
    cluster_to_parents: dict[int, set[Path]] = defaultdict(set)
    for index in range(record_count):
        record_start = HEADER_SIZE + index * RECORD_SIZE
        record = decode_record(data[record_start : record_start + RECORD_SIZE])
        candidates = files_by_name.get(record.title, ())
        if not candidates:
            continue
        matched_records += 1
        if len(candidates) != 1:
            continue
        unique_name_matches += 1
        path = candidates[0]
        cluster_to_parents[record.cluster_no].add(path.parent)

        words = struct.unpack_from("<17I", record.location_primary, 0)
        if words[8] == record.cluster_no:
            other_cluster = words[6]
        elif words[6] == record.cluster_no:
            other_cluster = words[8]
        else:
            other_cluster = None
        if other_cluster is not None and path.parent.parent.exists():
            if other_cluster == path.parent.parent.stat().st_ino:
                primary_grandparent_cluster_matches += 1

    single_parent_clusters = sum(
        len(parents) == 1 for parents in cluster_to_parents.values()
    )
    single_parent_records = sum(
        1
        for index in range(record_count)
        if (record := decode_record(
            data[HEADER_SIZE + index * RECORD_SIZE : HEADER_SIZE + (index + 1) * RECORD_SIZE]
        )).cluster_no in cluster_to_parents
        and len(cluster_to_parents[record.cluster_no]) == 1
        and len(files_by_name.get(record.title, ())) == 1
    )

    return {
        "record_count": record_count,
        "matched_records": matched_records,
        "unique_name_matches": unique_name_matches,
        "primary_grandparent_cluster_matches": primary_grandparent_cluster_matches,
        "cluster_count": len(cluster_to_parents),
        "single_parent_clusters": single_parent_clusters,
        "single_parent_records": single_parent_records,
    }


def main(library_path: Path, music_root: Path) -> None:
    summary = inspect_mounted_library(library_path.read_bytes(), music_root)
    print(
        "MOUNTED MUSIC.LIB PATH CORRELATION "
        f"records={summary['record_count']} "
        f"matched={summary['matched_records']} "
        f"unique={summary['unique_name_matches']} "
        f"primary-grandparent={summary['primary_grandparent_cluster_matches']} "
        f"clusters={summary['cluster_count']} "
        f"single-parent-clusters={summary['single_parent_clusters']} "
        f"single-parent-records={summary['single_parent_records']}"
    )


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: mounted_library_path_probe.py MUSIC.LIB MUSIC_ROOT")
    main(Path(sys.argv[1]), Path(sys.argv[2]))
