#!/usr/bin/env python3
"""Verify the safe, byte-level boundary of extracted ATJ2127 DSP members.

The DSP members use a different leading layout from the resident YQHX files:
``yqhx`` is at byte offset zero, not offset four.  This probe records that
fact and checks the local firmware corpus for accidental version drift.  It
does not assign meanings to the descriptor words and never executes payload
bytes.
"""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import struct


DSP_MEMBERS = (
    "adaac.dsp",
    "adaax.dsp",
    "adalac.dsp",
    "adape.dsp",
    "addsd.dsp",
    "adflac.dsp",
    "admp3.dsp",
    "adogg.dsp",
    "adppdrc.dsp",
    "adppext.dsp",
    "adtta.dsp",
    "adwavh.dsp",
    "adwma.dsp",
    "adwmal.dsp",
    "aemp3.dsp",
    "aewav.dsp",
    "audiopp.dsp",
    "jpeg.dsp",
)

CORPUS_NAMES = ("stock", "community", "first-pass-verify", "v1.101.11", "v1.101.12", "v5")
HEADER_WORDS = 16
HEADER_BYTES = HEADER_WORDS * 4
MARKERS = {
    "aewav.dsp": (b"RIFF", b"WAVE", b"fmt ", b"data"),
    "addsd.dsp": (b"DSD ", b"PROP", b"DBOD", b"CHAN"),
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_header(path: Path) -> tuple[int, tuple[int, ...], bytes]:
    data = path.read_bytes()
    if len(data) < HEADER_BYTES:
        raise AssertionError(f"{path} is shorter than the DSP descriptor")
    if data[:4] != b"yqhx":
        raise AssertionError(f"{path} has no yqhx marker at offset 0")
    if data[4:8] == b"yqhx":
        raise AssertionError(f"{path} unexpectedly uses the resident offset-4 marker")
    return len(data), struct.unpack_from("<16I", data), data


def marker_offsets(data: bytes, marker: bytes) -> tuple[int, ...]:
    offsets: list[int] = []
    start = 0
    while True:
        offset = data.find(marker, start)
        if offset < 0:
            return tuple(offsets)
        offsets.append(offset)
        start = offset + 1


def verify_member(path: Path) -> tuple[int, str, tuple[int, ...], dict[str, tuple[int, ...]]]:
    size, words, data = read_header(path)
    markers = {
        marker.decode("ascii"): marker_offsets(data, marker)
        for marker in MARKERS.get(path.name, ())
    }
    return size, sha256(data), words, markers


def verify_corpus(analysis_root: Path, members: tuple[str, ...] = DSP_MEMBERS) -> dict[str, tuple[int, str, tuple[int, ...], dict[str, tuple[int, ...]]]]:
    result = {}
    for member in members:
        observations = []
        for corpus in CORPUS_NAMES:
            path = analysis_root / corpus / "fw" / member
            if not path.is_file():
                raise AssertionError(f"missing DSP member: {path}")
            observations.append(verify_member(path))
        first = observations[0]
        if any(observation[:3] != first[:3] for observation in observations[1:]):
            raise AssertionError(f"DSP member differs across local corpus: {member}")
        result[member] = first
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--analysis-root", type=Path, required=True)
    parser.add_argument("--member", action="append", choices=DSP_MEMBERS)
    args = parser.parse_args()
    members = tuple(args.member) if args.member else DSP_MEMBERS
    result = verify_corpus(args.analysis_root, members)
    for member in members:
        size, digest, words, markers = result[member]
        marker_summary = "".join(
            f" {name}=" + ",".join(f"0x{offset:x}" for offset in offsets)
            for name, offsets in markers.items()
        )
        print(
            f"DSP CORPUS PASS {member} copies={len(CORPUS_NAMES)} "
            f"size=0x{size:x} sha256={digest}{marker_summary}"
        )
        if member in ("admp3.dsp", "aewav.dsp", "audiopp.dsp"):
            print(
                f"DSP HEADER PASS {member} "
                + "words="
                + ",".join(f"0x{word:08x}" for word in words[:11])
            )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
