#!/usr/bin/env python3
"""Verify the byte-level fade/buffer boundary in ``mmm_bp.al``.

The strings are useful navigation anchors, not proof that the strings
themselves perform attenuation.  The probe only checks their exact offsets,
the YQHX segment destinations, and corpus stability; it never executes the
processing module.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from yqhx import YqhxImage  # noqa: E402


CORPUS_NAMES = ("stock", "community", "first-pass-verify", "v1.101.11", "v1.101.12", "v5")
EXPECTED_SEGMENTS = (
    (0x400, 0x6F0, 0xBFC21400),
    (0xB00, 0xC00, 0xBFC27400),
)
FADE_MARKERS = {
    0x500: b"dsp dec error:",
    0x510: b"dsp dec slow!!",
    0x674: b"set_fast step:",
    0x684: b"set_slow step:",
    0x864: b"not enough data fade out!",
    0x880: b"buffer remain:",
    0xB70: b"fade out:",
    0xB7C: b"fade in:",
    0xD8C: b"frame space:",
}


def verify_member(path: Path) -> dict[int, int]:
    image = YqhxImage.load(path)
    actual_segments = tuple(
        (segment.file_offset, segment.length, segment.destination)
        for segment in image.segments
        if segment.length
    )
    if actual_segments[:2] != EXPECTED_SEGMENTS:
        raise AssertionError(f"unexpected mmm_bp segments: {actual_segments!r}")
    data = path.read_bytes()
    for offset, marker in FADE_MARKERS.items():
        if data[offset : offset + len(marker)] != marker:
            raise AssertionError(f"missing mmm_bp marker at 0x{offset:x}: {marker!r}")
    return FADE_MARKERS.copy()


def verify_corpus(analysis_root: Path) -> dict[int, int]:
    observations = []
    for corpus in CORPUS_NAMES:
        path = analysis_root / corpus / "fw" / "mmm_bp.al"
        if not path.is_file():
            raise AssertionError(f"missing mmm_bp member: {path}")
        observations.append(verify_member(path))
    first = observations[0]
    if any(observation != first for observation in observations[1:]):
        raise AssertionError("mmm_bp fade/buffer markers differ across local corpus")
    return first


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--analysis-root", type=Path, required=True)
    args = parser.parse_args()
    markers = verify_corpus(args.analysis_root)
    offsets = ",".join(f"0x{offset:x}" for offset in markers)
    print(f"MMM BP BINARY PASS copies={len(CORPUS_NAMES)} markers={offsets} segments=2")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
