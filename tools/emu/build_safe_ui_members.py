#!/usr/bin/env python3
"""Build and verify the safe UI candidate at extracted-member level.

This deliberately stops before FWU repacking. It takes an extracted community
firmware directory, applies only the default-art, transport-icon, and upgrade
prompt resource changes, and verifies that exactly ``music.sty`` and
``setting.sty`` differ from the source directory. The default source
validation only accepts the two observed ``music.sty`` layouts.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import shutil
import sys


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))

from patch_default_art import patch as patch_default_art  # noqa: E402
from patch_music_controls import patch as patch_music_controls  # noqa: E402
from patch_upgrade_prompt import patch as patch_upgrade_prompt  # noqa: E402

from candidate_delta_probe import verify_exact_delta  # noqa: E402


EXPECTED_CHANGED_MEMBERS = ("music.sty", "setting.sty")


def build(
    source: Path,
    candidate: Path,
    preview: Path | None = None,
    validate_source: bool = True,
) -> tuple[str, ...]:
    """Create a verified extracted safe-UI candidate without overwriting data."""
    if not source.is_dir():
        raise ValueError(f"source firmware directory does not exist: {source}")
    if candidate.exists():
        raise ValueError(f"candidate directory already exists: {candidate}")

    members = sorted(path for path in source.iterdir() if path.is_file())
    if not members:
        raise ValueError(f"source firmware directory has no members: {source}")

    required = {"music.sty", "setting.sty"}
    missing = sorted(required - {path.name for path in members})
    if missing:
        raise ValueError(f"source firmware directory is missing: {missing!r}")

    candidate.mkdir(parents=True)
    for member in members:
        shutil.copy2(member, candidate / member.name)

    preview_path = (
        preview
        if preview is not None
        else candidate.parent / f"{candidate.name}-music-preview.ppm"
    )
    if candidate.resolve() in preview_path.resolve().parents:
        raise ValueError("preview must be outside the candidate member directory")

    patch_default_art(
        candidate / "music.sty",
        candidate / "music.sty",
        preview_path,
        validate_source=validate_source,
    )
    patch_music_controls(
        candidate / "music.sty",
        candidate / "music.sty",
        validate_source=validate_source,
    )
    patch_upgrade_prompt(
        source / "setting.sty",
        candidate / "setting.sty",
        validate_source=validate_source,
    )

    return verify_exact_delta(
        source,
        candidate,
        EXPECTED_CHANGED_MEMBERS,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="extracted community fw directory")
    parser.add_argument("candidate", type=Path, help="new extracted candidate directory")
    parser.add_argument(
        "--preview",
        type=Path,
        help="optional PPM path for the generated missing-artwork preview",
    )
    args = parser.parse_args()

    changed = build(args.source, args.candidate, args.preview)
    print(
        "SAFE UI MEMBERS PASS "
        f"source={args.source} candidate={args.candidate} "
        f"changed={','.join(changed)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
