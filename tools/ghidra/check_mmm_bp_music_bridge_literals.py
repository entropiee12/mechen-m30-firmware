#!/usr/bin/env python3
"""Bounded aligned-word scan for direct mmm_bp <-> music/engine links.

This does not resolve computed pointers, resident services, or runtime state.
It checks only four hash-pinned extracted members and exact 32-bit literals.
"""

from __future__ import annotations

import argparse
import hashlib
import struct
from pathlib import Path


PINNED = {
    "stock": {
        "music.ap": "faa6b68a68de78ef45c2a9d9ad664b6b6310a93f21f9a07b29467827abd49193",
        "mengine.ap": "1340bac3cb68043f7a9651ee7959c2f502e6759c7debe0626f07ef9231896d4d",
        "mmm_bp.al": "ab5d98b578406c4e04fb7b546317fbec0d36ce4a7c139ef0922b06bb4b753c5b",
        "mmm_mp.al": "57b541ad791637e30135db6c114da98d5680b5f0907ffabf74bf57fa4977a199",
    },
    "community": {
        "music.ap": "662e379602707a9c4b0dcd6b94b591dd77c2b5f7a557fcb03b8c2a5c9b1baac3",
        "mengine.ap": "b569605e9f66ef94023cf82d665ae119f6bbb7ed0c9d8f5e6c5be867ce91be49",
        "mmm_bp.al": "ab5d98b578406c4e04fb7b546317fbec0d36ce4a7c139ef0922b06bb4b753c5b",
        "mmm_mp.al": "57b541ad791637e30135db6c114da98d5680b5f0907ffabf74bf57fa4977a199",
    },
}

DECODER_OWN = (0x9FC21B00, 0x9FC21B04)
DECODER_EXTERNAL = (0x9FC15418, 0x9FC15738, 0x9FC15D8C, 0x9FC15CB4)
DECODER_CODE = 0xBFC216A9


def aligned_words(path: Path) -> tuple[str, dict[int, list[int]]]:
    data = path.read_bytes()
    sha = hashlib.sha256(data).hexdigest()
    words: dict[int, list[int]] = {}
    for offset in range(0, len(data) - 3, 4):
        value = struct.unpack_from("<I", data, offset)[0]
        words.setdefault(value, []).append(offset)
    return sha, words


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("analysis_root", type=Path, help="directory containing stock/fw and community/fw")
    root = parser.parse_args().analysis_root

    for variant, expected in PINNED.items():
        images: dict[str, dict[int, list[int]]] = {}
        for name, sha_expected in expected.items():
            path = root / variant / "fw" / name
            sha, words = aligned_words(path)
            if sha != sha_expected:
                raise SystemExit(f"{variant}/{name}: SHA-256 mismatch: {sha}")
            images[name] = words
            print(f"HASH {variant}/{name} {sha}")

        checks = {
            "music.ap has no decoder context/code literals": (
                "music.ap", (*DECODER_OWN, DECODER_CODE)
            ),
            "mengine.ap has no decoder context/code literals": (
                "mengine.ap", (*DECODER_OWN, DECODER_CODE)
            ),
            "mmm_mp.al has no decoder context/code literals": (
                "mmm_mp.al", (*DECODER_OWN, DECODER_CODE)
            ),
            "mmm_bp.al has no music/engine state literals": (
                "mmm_bp.al", DECODER_EXTERNAL
            ),
        }
        for label, (member, values) in checks.items():
            hits = [(value, images[member].get(value, [])) for value in values if images[member].get(value)]
            if hits:
                formatted = [(f"0x{value:08x}", [f"0x{o:x}" for o in offsets]) for value, offsets in hits]
                raise SystemExit(f"UNEXPECTED literal {variant}/{member}: {label}: {formatted}")
            print(f"NO-DIRECT-LITERAL {variant}/{member}: {label}")

        for member, values in (("mmm_bp.al", DECODER_OWN),):
            for value in values:
                if value not in images[member]:
                    raise SystemExit(f"expected control literal absent: {variant}/{member} 0x{value:08x}")
        print(f"DECODER-OWN-STATE-LITERALS {variant}: mmm_bp.al=0x9fc21b00/0x9fc21b04")

    print("BOUNDED BRIDGE SCAN PASS: no aligned direct cross-module pointer/state literals in pinned members")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
