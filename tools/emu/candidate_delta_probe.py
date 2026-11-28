#!/usr/bin/env python3
"""Verify an extracted firmware candidate's exact member-level delta."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def member_names(directory: Path) -> tuple[str, ...]:
    if not directory.is_dir():
        raise AssertionError(f"firmware directory does not exist: {directory}")

    return tuple(sorted(path.name for path in directory.iterdir() if path.is_file()))


def verify_exact_delta(
    baseline: Path,
    candidate: Path,
    expected_changed: tuple[str, ...],
    require_same_size: bool = True,
) -> tuple[str, ...]:
    """Verify that only the named extracted members differ."""

    baseline_names = member_names(baseline)
    candidate_names = member_names(candidate)
    if baseline_names != candidate_names:
        missing = sorted(set(baseline_names) - set(candidate_names))
        extra = sorted(set(candidate_names) - set(baseline_names))
        raise AssertionError(f"member set changed: missing={missing!r}, extra={extra!r}")

    actual_changed: list[str] = []
    for name in baseline_names:
        baseline_path = baseline / name
        candidate_path = candidate / name
        if sha256(baseline_path) != sha256(candidate_path):
            actual_changed.append(name)
            if require_same_size and baseline_path.stat().st_size != candidate_path.stat().st_size:
                raise AssertionError(f"member size changed: {name}")

    actual = tuple(actual_changed)
    expected = tuple(sorted(set(expected_changed)))
    if actual != expected:
        raise AssertionError(f"unexpected member delta: expected={expected!r}, actual={actual!r}")

    return actual


def build_manifest(
    baseline: Path,
    candidate: Path,
    expected_changed: tuple[str, ...],
    require_same_size: bool = True,
) -> dict[str, Any]:
    """Return an explicit extracted-member delta manifest.

    The result is deliberately marked non-flashable: it describes extracted
    members and cannot replace full HEX/FWU checksum and round-trip checks.
    """
    changed = verify_exact_delta(
        baseline,
        candidate,
        expected_changed,
        require_same_size=require_same_size,
    )
    members: dict[str, dict[str, Any]] = {}
    for name in member_names(baseline):
        baseline_path = baseline / name
        candidate_path = candidate / name
        baseline_hash = sha256(baseline_path)
        candidate_hash = sha256(candidate_path)
        members[name] = {
            "baseline_sha256": baseline_hash,
            "candidate_sha256": candidate_hash,
            "baseline_size": baseline_path.stat().st_size,
            "candidate_size": candidate_path.stat().st_size,
            "changed": baseline_hash != candidate_hash,
        }

    return {
        "kind": "m30-extracted-member-candidate",
        "flashable_hex": False,
        "rollback_reference": str(baseline),
        "candidate": str(candidate),
        "expected_changed_members": list(sorted(set(expected_changed))),
        "changed_members": list(changed),
        "member_count": len(members),
        "members": members,
    }


def write_manifest(
    baseline: Path,
    candidate: Path,
    expected_changed: tuple[str, ...],
    output: Path,
    require_same_size: bool = True,
) -> dict[str, Any]:
    """Validate a candidate and write its JSON manifest outside the tree."""
    manifest = build_manifest(
        baseline,
        candidate,
        expected_changed,
        require_same_size=require_same_size,
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path, help="extracted baseline fw directory")
    parser.add_argument("candidate", type=Path, help="extracted candidate fw directory")
    parser.add_argument(
        "--changed",
        action="append",
        required=True,
        dest="expected_changed",
        help="member expected to differ; repeat for each member",
    )
    parser.add_argument(
        "--allow-size-change",
        action="store_true",
        help="allow changed members to have a different size",
    )
    parser.add_argument(
        "--manifest",
        type=Path,
        help="write a JSON delta manifest; this remains an extracted-tree artifact",
    )
    args = parser.parse_args()
    expected_changed = tuple(args.expected_changed)
    if args.manifest:
        manifest = write_manifest(
            args.baseline,
            args.candidate,
            expected_changed,
            args.manifest,
            require_same_size=not args.allow_size_change,
        )
        changed = tuple(manifest["changed_members"])
    else:
        changed = verify_exact_delta(
            args.baseline,
            args.candidate,
            expected_changed,
            require_same_size=not args.allow_size_change,
        )
    print(
        "CANDIDATE DELTA PASS "
        f"baseline={args.baseline} candidate={args.candidate} "
        f"changed={','.join(changed)}"
        + (f" manifest={args.manifest}" if args.manifest else "")
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
