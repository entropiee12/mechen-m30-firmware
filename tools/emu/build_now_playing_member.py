#!/usr/bin/env python3
"""Build the extracted, resource-only now-playing UI candidate.

This is deliberately one boundary below a flashable HEX.  It copies an
extracted firmware tree, applies only the verified missing-artwork and
transport-icon changes in ``music.sty``, and fails closed unless that is the
only member delta.  The host font/status overlay is not written to firmware.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import shutil
import sys


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))

from candidate_delta_probe import verify_exact_delta  # noqa: E402
from patch_default_art import patch as patch_default_art  # noqa: E402
from patch_music_controls import patch as patch_music_controls  # noqa: E402


EXPECTED_CHANGED_MEMBERS = ("music.sty",)


def build(
    source: Path,
    candidate: Path,
    preview: Path | None = None,
    validate_source: bool = True,
) -> tuple[str, ...]:
    """Create a copy-safe extracted candidate with only ``music.sty`` changed."""
    if not source.is_dir():
        raise ValueError(f"source firmware directory does not exist: {source}")
    if candidate.exists():
        raise ValueError(f"candidate directory already exists: {candidate}")
    members = sorted(path for path in source.iterdir() if path.is_file())
    if "music.sty" not in {path.name for path in members}:
        raise ValueError(f"source firmware is missing music.sty: {source}")

    candidate.mkdir(parents=True)
    for member in members:
        shutil.copy2(member, candidate / member.name)

    preview_path = preview or candidate.parent / f"{candidate.name}-preview.ppm"
    if candidate.resolve() in preview_path.resolve().parents:
        raise ValueError("preview must be outside the candidate member directory")
    patch_default_art(candidate / "music.sty", candidate / "music.sty", preview_path, validate_source=validate_source)
    patch_music_controls(candidate / "music.sty", candidate / "music.sty", validate_source=validate_source)
    return verify_exact_delta(source, candidate, EXPECTED_CHANGED_MEMBERS)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="extracted community firmware directory")
    parser.add_argument("candidate", type=Path, help="new extracted candidate directory")
    parser.add_argument("--preview", type=Path, help="optional PPM path for the artwork preview")
    args = parser.parse_args()
    changed = build(args.source, args.candidate, args.preview)
    print(
        "NOW-PLAYING MEMBER PASS "
        f"source={args.source} candidate={args.candidate} changed={','.join(changed)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
