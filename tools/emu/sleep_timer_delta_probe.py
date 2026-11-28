#!/usr/bin/env python3
"""Verify the extracted config.ap delta for the persistent sleep setting.

This probe checks only the cross-version byte delta. It does not claim the
timer unit, selectable values, countdown behavior, or config-record layout.
"""

from __future__ import annotations

import argparse
from pathlib import Path


CONFIG_BANK_FILE_OFFSET = 0x1400
CONFIG_BANK_VIRTUAL_ADDRESS = 0x40054C00
EXPECTED_DIFF_COUNT = 338
EXPECTED_DIFF_FILE_START = 0x1798
EXPECTED_DIFF_FILE_END = 0x1B83


def differing_offsets(before: bytes, after: bytes) -> tuple[int, ...]:
    """Return file offsets whose bytes differ between two config members."""
    if len(before) != len(after):
        raise AssertionError(
            f"config.ap lengths differ: {len(before):#x} != {len(after):#x}"
        )

    return tuple(
        offset
        for offset, (before_byte, after_byte) in enumerate(zip(before, after))
        if before_byte != after_byte
    )


def verify_delta(before: bytes, after: bytes) -> tuple[int, ...]:
    """Verify the known 2022-12-16 to 2023-02-20 delta boundary."""
    offsets = differing_offsets(before, after)
    if len(offsets) != EXPECTED_DIFF_COUNT:
        raise AssertionError(
            f"sleep-timer delta has {len(offsets)} bytes; "
            f"expected {EXPECTED_DIFF_COUNT}"
        )

    if offsets[0] != EXPECTED_DIFF_FILE_START or offsets[-1] != EXPECTED_DIFF_FILE_END:
        raise AssertionError(
            "sleep-timer delta file span "
            f"{offsets[0]:#x}..{offsets[-1]:#x} != "
            f"{EXPECTED_DIFF_FILE_START:#x}..{EXPECTED_DIFF_FILE_END:#x}"
        )

    if any(
        offset < EXPECTED_DIFF_FILE_START or offset > EXPECTED_DIFF_FILE_END
        for offset in offsets
    ):
        raise AssertionError("sleep-timer delta contains a byte outside the expected span")

    return offsets


def virtual_span(offsets: tuple[int, ...]) -> tuple[int, int]:
    """Translate file offsets in the mapped config bank to virtual addresses."""
    return (
        CONFIG_BANK_VIRTUAL_ADDRESS + offsets[0] - CONFIG_BANK_FILE_OFFSET,
        CONFIG_BANK_VIRTUAL_ADDRESS + offsets[-1] - CONFIG_BANK_FILE_OFFSET,
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("before_config_ap", type=Path)
    parser.add_argument("after_config_ap", type=Path)
    args = parser.parse_args()

    offsets = verify_delta(
        args.before_config_ap.read_bytes(),
        args.after_config_ap.read_bytes(),
    )
    virtual_start, virtual_end = virtual_span(offsets)
    print(
        "SLEEP TIMER DELTA PASS "
        f"bytes={len(offsets)} file={offsets[0]:#x}..{offsets[-1]:#x} "
        f"virtual={virtual_start:#x}..{virtual_end:#x}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
