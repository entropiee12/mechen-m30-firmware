#!/usr/bin/env python3
"""Verify the static filesystem-driver boundary used by the M30.

This probe parses only the yqhx container and resident data.  It does not
execute driver bytes or touch a card.  Ghidra is used separately by
``run_filesystem_probe.sh`` for the control-flow observations recorded in the
findings note.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "emu"))

from yqhx import YqhxImage  # noqa: E402


EXTENSIONS = (
    "MP3",
    "WMA",
    "WAV",
    "AAC",
    "FLAC",
    "APE",
    "AVI",
    "TXT",
    "JPG",
    "JPEG",
    "DSF",
    "DFF",
    "LRC",
    "ASF",
    "OGG",
    "TTA",
    "ISO",
    "AAX",
    "AA",
    "MP1",
    "MP2",
    "M4A",
    "POS",
    "ACT",
    "BMK",
    "AIF",
    "APK",
    "PCM",
    "**",
    "*f",
    "   ",
)


@dataclass(frozen=True)
class DriverContract:
    name: str
    segments: tuple[tuple[int, int, int], ...]
    bss: tuple[int, int]
    entry: int
    table_header_offset: int
    table_offset: int
    sha256: str


CONTRACTS = {
    "exfatn.drv": DriverContract(
        name="exfatn.drv",
        segments=((0x200, 0x800, 0xBFC16C00), (0xA00, 0x300, 0x9FC18000)),
        bss=(0x9FC18300, 0xA00),
        entry=0x240094D8,
        table_header_offset=0x7F4,
        table_offset=0x7F8,
        sha256="6c6b724a86f371cf4cbca7ab5f1b0c970d27ecebd502d3c7edb1e5e895d3c0b5",
    ),
    "fat_32n.drv": DriverContract(
        name="fat_32n.drv",
        segments=((0x200, 0x800, 0xBFC16C00), (0xA00, 0x300, 0x9FC18000)),
        bss=(0x9FC18300, 0xA00),
        entry=0x230096F4,
        table_header_offset=0x77C,
        table_offset=0x780,
        sha256="bed63d62aff4df62254b758050abd4ee72f3a829dc39ba374485e64875c75b6b",
    ),
}


def read_extension_slots(data: bytes, contract: DriverContract) -> tuple[str, ...]:
    """Return the fixed four-byte extension slots used by the matcher."""

    header = data[contract.table_header_offset : contract.table_offset]
    if header != b"\x00\x00\xff\xff":
        raise AssertionError(
            f"{contract.name} extension-table header {header.hex()} != 0000ffff"
        )
    end = contract.table_offset + len(EXTENSIONS) * 4
    raw = data[contract.table_offset:end]
    if len(raw) != len(EXTENSIONS) * 4:
        raise AssertionError(f"{contract.name} extension table is truncated")
    slots = tuple(raw[index : index + 4].rstrip(b"\x00").decode("ascii")
                  for index in range(0, len(raw), 4))
    if slots != EXTENSIONS:
        raise AssertionError(f"{contract.name} extension slots {slots!r} != expected list")
    return slots


def verify_driver(path: Path) -> tuple[DriverContract, tuple[str, ...]]:
    contract = CONTRACTS.get(path.name)
    if contract is None:
        raise AssertionError(f"unsupported filesystem driver name: {path.name}")
    data = path.read_bytes()
    image = YqhxImage.load(path)
    actual_segments = tuple(
        (segment.file_offset, segment.length, segment.destination)
        for segment in image.segments
        if segment.length
    )
    if actual_segments != contract.segments:
        raise AssertionError(
            f"{path.name} segments {actual_segments!r} != {contract.segments!r}"
        )
    if (image.bss_address, image.bss_size) != contract.bss:
        raise AssertionError(
            f"{path.name} BSS {(image.bss_address, image.bss_size)!r} != {contract.bss!r}"
        )
    if image.entry != contract.entry:
        raise AssertionError(f"{path.name} entry 0x{image.entry:08x} != 0x{contract.entry:08x}")
    digest = hashlib.sha256(data).hexdigest()
    if digest != contract.sha256:
        raise AssertionError(f"{path.name} SHA256 {digest} != {contract.sha256}")
    if data[0x04:0x08] != b"yqhx":
        raise AssertionError(f"{path.name} missing yqhx magic")
    return contract, read_extension_slots(data, contract)


def verify_corpus(corpus_root: Path) -> dict[str, tuple[str, ...]]:
    """Verify that every extracted firmware copy has the same driver bytes."""

    results: dict[str, tuple[str, ...]] = {}
    for name, contract in CONTRACTS.items():
        paths = sorted(corpus_root.glob(f"*/fw/{name}"))
        if not paths:
            raise AssertionError(f"no corpus copies found for {name}")
        hashes = {hashlib.sha256(path.read_bytes()).hexdigest() for path in paths}
        if hashes != {contract.sha256}:
            raise AssertionError(f"{name} corpus hashes {sorted(hashes)!r}")
        results[name] = tuple(str(path) for path in paths)
    return results


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("exfat_driver", type=Path)
    parser.add_argument("fat_driver", type=Path)
    parser.add_argument(
        "--corpus-root",
        type=Path,
        default=ROOT / "analysis",
        help="analysis directory containing */fw/driver copies",
    )
    args = parser.parse_args()

    for path in (args.exfat_driver, args.fat_driver):
        contract, slots = verify_driver(path)
        print(
            "FILESYSTEM BINARY PASS "
            f"{contract.name} segments={len(contract.segments)} "
            f"bss=0x{contract.bss[0]:08x}+0x{contract.bss[1]:x} "
            f"entry=0x{contract.entry:08x} extensions={len(slots)}"
        )

    corpus = verify_corpus(args.corpus_root)
    for name, paths in corpus.items():
        print(f"FILESYSTEM CORPUS PASS {name} copies={len(paths)} sha256={CONTRACTS[name].sha256}")
    print(
        "FILESYSTEM TABLE PASS slots=31 "
        "header=0000ffff exfat/fat-extension-order=identical"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
