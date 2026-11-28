#!/usr/bin/env python3
"""Validate the observed read-only M3U.LIB container boundary."""

from __future__ import annotations

import hashlib
from pathlib import Path
import struct
import sys


SECTOR_SIZE = 512
M3U_HEADER_BYTES = SECTOR_SIZE
M3U_MAGIC = b"M3U\x00"
M3U_MARKER_OFFSET = 0x10
M3U_MARKER = b"ALLFILE"
M3U_ENTRY_COUNT_OFFSET = 0x08
M3U_RECORD_START_SECTOR_OFFSET = 0x0A
EXPECTED_DEVICE_SHA256 = (
    "ad4deb5b599eb97309e49a1d907cab445e3785d04f0ee103e6126f8ab0703a01"
)


def inspect_m3u_library(
    data: bytes,
    expected_sha256: str | None = EXPECTED_DEVICE_SHA256,
) -> dict[str, object]:
    """Validate the observed header and report the remaining byte count.

    Payload bytes are intentionally left uninterpreted.  The supplied image's
    payload is stale MP3 data, not an example of the firmware's playlist ABI.
    """
    if len(data) % SECTOR_SIZE:
        raise ValueError("M3U.LIB image must contain complete 512-byte sectors")
    if len(data) < M3U_HEADER_BYTES:
        raise ValueError("M3U.LIB image is shorter than its header sector")

    sha256 = hashlib.sha256(data).hexdigest()
    if expected_sha256 is not None and sha256 != expected_sha256:
        raise ValueError(
            f"M3U.LIB SHA-256 {sha256} does not match {expected_sha256}"
        )

    header = data[:M3U_HEADER_BYTES]
    if header[: len(M3U_MAGIC)] != M3U_MAGIC:
        raise ValueError("M3U.LIB has an unexpected magic")
    marker_end = M3U_MARKER_OFFSET + len(M3U_MARKER)
    if header[M3U_MARKER_OFFSET:marker_end] != M3U_MARKER:
        raise ValueError("M3U.LIB has an unexpected ALLFILE marker")
    if struct.unpack_from("<H", header, M3U_ENTRY_COUNT_OFFSET)[0] != 0:
        raise ValueError("M3U.LIB entry-count field at 0x08 is not zero")

    payload_bytes = len(data) - M3U_HEADER_BYTES

    return {
        "bytes": len(data),
        "sha256": sha256,
        "header_bytes": M3U_HEADER_BYTES,
        "payload_bytes": payload_bytes,
        "entry_count_field": struct.unpack_from(
            "<H", header, M3U_ENTRY_COUNT_OFFSET
        )[0],
        "record_start_sector": struct.unpack_from(
            "<H", header, M3U_RECORD_START_SECTOR_OFFSET
        )[0],
    }


def main(path: Path, expected_sha256: str | None) -> None:
    summary = inspect_m3u_library(path.read_bytes(), expected_sha256=expected_sha256)
    print(
        "M3U.LIB EVIDENCE PASS "
        f"bytes={summary['bytes']} "
        f"payload_bytes={summary['payload_bytes']} "
        f"entry_count_field={summary['entry_count_field']} "
        f"record_start_sector={summary['record_start_sector']} "
        f"sha256={summary['sha256']}"
    )


if __name__ == "__main__":
    if len(sys.argv) not in (2, 3):
        raise SystemExit("usage: m3u_library_evidence_probe.py M3U.LIB [EXPECTED_SHA256| -]")

    expected_sha256 = (
        EXPECTED_DEVICE_SHA256
        if len(sys.argv) == 2 or sys.argv[2] != "-"
        else None
    )
    if len(sys.argv) == 3 and sys.argv[2] != "-":
        expected_sha256 = sys.argv[2]

    main(Path(sys.argv[1]), expected_sha256)
