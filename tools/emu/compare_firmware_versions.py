#!/usr/bin/env python3
"""Compare extracted M30 firmware versions at the provider boundary.

The input directory contains one subdirectory per version.  Each version
subdirectory must contain an extracted ``fw`` directory, as produced by
``atjboottool --fw``.  Module hashes are reported separately from pointer
counts so a known consumer such as ``drv_ui.drv`` is not mistaken for a
provider.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from dataclasses import asdict, dataclass
from pathlib import Path

from provider_scan import OBSERVED_POINTERS, scan_tree


DEFAULT_MODULES = ("drv_ui.drv", "kernel.drv", "browser.ap", "music.ap")
DEFAULT_EXCLUDES = ("drv_ui.drv",)


@dataclass(frozen=True)
class VersionReport:
    version: str
    module_hashes: dict[str, str | None]
    pointer_counts: dict[int, int]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def extracted_versions(root: Path) -> tuple[Path, ...]:
    return tuple(
        sorted(
            (path for path in root.iterdir() if path.is_dir() and (path / "fw").is_dir()),
            key=lambda path: path.name,
        )
    )


def compare_versions(
    root: Path,
    pointers: tuple[int, ...] = tuple(OBSERVED_POINTERS),
    module_names: tuple[str, ...] = DEFAULT_MODULES,
    exclude_names: tuple[str, ...] = DEFAULT_EXCLUDES,
) -> tuple[VersionReport, ...]:
    reports: list[VersionReport] = []
    for version_dir in extracted_versions(root):
        fw = version_dir / "fw"
        module_hashes = {
            name: sha256(fw / name) if (fw / name).is_file() else None
            for name in module_names
        }
        occurrences = scan_tree(fw, pointers, exclude_names)
        pointer_counts = {pointer: len(occurrences.get(pointer, ())) for pointer in pointers}
        reports.append(VersionReport(version_dir.name, module_hashes, pointer_counts))
    return tuple(reports)


def _json_reports(reports: tuple[VersionReport, ...]) -> list[dict[str, object]]:
    return [
        {
            **asdict(report),
            "pointer_counts": {
                f"0x{pointer:08x}": count
                for pointer, count in report.pointer_counts.items()
            },
        }
        for report in reports
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path, help="root containing extracted version directories")
    parser.add_argument("--json", action="store_true", dest="as_json")
    parser.add_argument(
        "--pointer",
        action="append",
        type=lambda value: int(value, 0),
        dest="pointers",
        help="pointer to scan; repeatable (defaults to the observed UI set)",
    )
    parser.add_argument(
        "--module",
        action="append",
        dest="modules",
        help="module to hash; repeatable (defaults to drv_ui/kernel/browser/music)",
    )
    parser.add_argument(
        "--exclude-name",
        action="append",
        default=list(DEFAULT_EXCLUDES),
        help="basename excluded from provider scanning; repeatable",
    )
    parser.add_argument(
        "--require-zero",
        action="store_true",
        help="fail if any selected pointer occurs outside excluded consumers",
    )
    args = parser.parse_args()
    pointers = tuple(args.pointers) if args.pointers else tuple(OBSERVED_POINTERS)
    modules = tuple(args.modules) if args.modules else DEFAULT_MODULES
    reports = compare_versions(Path(args.root), pointers, modules, tuple(args.exclude_name))
    if args.as_json:
        print(json.dumps(_json_reports(reports), indent=2))
    else:
        for report in reports:
            print(f"[{report.version}]")
            for name, digest in report.module_hashes.items():
                print(f"  {name}: {digest or 'missing'}")
            for pointer, count in report.pointer_counts.items():
                print(f"  0x{pointer:08x}: {count} provider occurrence(s)")
    if args.require_zero:
        nonzero = {
            pointer
            for report in reports
            for pointer, count in report.pointer_counts.items()
            if count
        }
        if nonzero:
            print(
                "unexpected provider occurrences: "
                + ", ".join(f"0x{pointer:08x}" for pointer in sorted(nonzero)),
                file=sys.stderr,
            )
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
