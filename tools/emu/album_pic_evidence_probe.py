#!/usr/bin/env python3
"""Validate the observed device ``ALBUM.PIC`` container."""

from __future__ import annotations

import hashlib
from pathlib import Path
import sys

TOOLS_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS_ROOT / "sim"))

from artwork_cache import decode_album_pic_container


EXPECTED_DEVICE_SHA256 = (
    "65bad6f0a143f3990f1d0e958699513159dc3b06f7bcec0452ee318217c8e671"
)


def inspect_album_pic(
    data: bytes,
    expected_sha256: str | None = EXPECTED_DEVICE_SHA256,
) -> dict[str, object]:
    """Validate a device image and return its bounded container summary."""
    sha256 = hashlib.sha256(data).hexdigest()

    if expected_sha256 is not None and sha256 != expected_sha256:
        raise ValueError(
            f"ALBUM.PIC SHA-256 {sha256} does not match {expected_sha256}"
        )

    container = decode_album_pic_container(data)

    return {
        "bytes": len(data),
        "sha256": sha256,
        "width": container.width,
        "height": container.height,
        "payload_bytes": len(container.pixels),
        "padding_bytes": len(container.padding),
    }


def main(path: Path, expected_sha256: str | None) -> None:
    summary = inspect_album_pic(
        path.read_bytes(),
        expected_sha256=expected_sha256,
    )
    print(
        "ALBUM.PIC EVIDENCE PASS "
        f"bytes={summary['bytes']} "
        f"dimensions={summary['width']}x{summary['height']} "
        f"payload_bytes={summary['payload_bytes']} "
        f"padding_bytes={summary['padding_bytes']} "
        f"sha256={summary['sha256']}"
    )


if __name__ == "__main__":
    if len(sys.argv) not in (2, 3):
        raise SystemExit("usage: album_pic_evidence_probe.py ALBUM.PIC [EXPECTED_SHA256| -]")

    expected_sha256 = (
        EXPECTED_DEVICE_SHA256
        if len(sys.argv) == 2 or sys.argv[2] != "-"
        else None
    )
    if len(sys.argv) == 3 and sys.argv[2] != "-":
        expected_sha256 = sys.argv[2]

    main(Path(sys.argv[1]), expected_sha256)
