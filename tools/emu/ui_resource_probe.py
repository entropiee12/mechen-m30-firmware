#!/usr/bin/env python3
"""Check the extracted MCG/RU3 resource boundaries without rendering them."""

from __future__ import annotations

import argparse
import hashlib
import re
from pathlib import Path
import struct


MCG_MEMBERS = ("browser.mcg", "mainmenu.mcg")
STY_MEMBERS = ("browser.sty", "config.sty", "udisk.sty", "upgrade.sty")
CORPUS_NAMES = ("stock", "community", "first-pass-verify", "v1.101.11", "v1.101.12", "v5")
MCG_MAGIC = b"ACTMCGGM01"
RU3_MAGIC = b"RU3\x19"
NODE_RE = re.compile(
    rb"\[NODE(\d+)\]\s*\r?\n"
    rb"name=(.*?)\r?\n"
    rb"index=(\d+)\s*\r?\n"
    rb"id=(\d+)\s*\r?\n"
    rb"layer=(\d+)\s*\r?\n",
    re.DOTALL,
)
INFO_RE = re.compile(rb"\[INFO\]\s*\r?\nfilesum=(\d+)")


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def parse_mcg(path: Path) -> tuple[int, str, tuple[tuple[int, int, int, int], ...]]:
    data = path.read_bytes()
    if MCG_MAGIC not in data:
        raise AssertionError(f"{path} has no MCG marker")
    nodes = tuple(
        (int(number), int(index), int(node_id), int(layer), raw_name)
        for number, raw_name, index, node_id, layer in NODE_RE.findall(data)
    )
    info = INFO_RE.search(data)
    if info is None:
        raise AssertionError(f"{path} has no MCG filesum")
    if int(info.group(1)) != len(nodes):
        raise AssertionError(f"{path} filesum disagrees with node records")
    if tuple(node[1] for node in nodes) != tuple(range(1, len(nodes) + 1)):
        raise AssertionError(f"{path} node indexes are not contiguous")
    return len(data), digest(data), nodes


def parse_sty(path: Path) -> tuple[int, str, int, int, tuple[tuple[int, int], ...]]:
    data = path.read_bytes()
    if data[:4] != RU3_MAGIC:
        raise AssertionError(f"{path} is not an RU3 resource")
    if len(data) < 0x10:
        raise AssertionError(f"{path} is too short for an RU3 header")
    style_addr = struct.unpack_from("<I", data, 8)[0]
    if style_addr + 0x0C > len(data):
        raise AssertionError(f"{path} style address is outside the member")
    style_begin = struct.unpack_from("<I", data, style_addr + 8)[0]
    if style_begin + 8 > len(data):
        raise AssertionError(f"{path} style table is outside the member")
    groups: list[tuple[int, int]] = []
    for index in range(32):
        if style_begin + index * 8 + 8 > len(data):
            break
        address, count = struct.unpack_from("<II", data, style_begin + index * 8)
        if address == 0 and count == 0:
            break
        if address >= len(data):
            raise AssertionError(f"{path} group {index} address is outside the member")
        groups.append((address, count))
    if not groups:
        raise AssertionError(f"{path} has no non-empty RU3 groups")
    return len(data), digest(data), style_addr, style_begin, tuple(groups)


def verify_corpus(analysis_root: Path) -> dict[str, object]:
    result: dict[str, object] = {}
    for member in MCG_MEMBERS + STY_MEMBERS:
        observations = []
        for corpus in CORPUS_NAMES:
            path = analysis_root / corpus / "fw" / member
            if not path.is_file():
                raise AssertionError(f"missing UI resource: {path}")
            observations.append(parse_mcg(path) if member.endswith(".mcg") else parse_sty(path))
        first = observations[0]
        if any(observation != first for observation in observations[1:]):
            raise AssertionError(f"UI resource differs across local corpus: {member}")
        result[member] = first
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--analysis-root", type=Path, required=True)
    args = parser.parse_args()
    result = verify_corpus(args.analysis_root)
    for member in MCG_MEMBERS:
        size, digest_value, nodes = result[member]
        print(
            f"MCG CORPUS PASS {member} copies={len(CORPUS_NAMES)} size=0x{size:x} "
            f"sha256={digest_value} nodes={len(nodes)} "
            f"ids=" + ",".join(str(node[2]) for node in nodes)
        )
    for member in STY_MEMBERS:
        size, digest_value, style_addr, style_begin, groups = result[member]
        print(
            f"RU3 CORPUS PASS {member} copies={len(CORPUS_NAMES)} size=0x{size:x} "
            f"sha256={digest_value} style=0x{style_addr:x}/0x{style_begin:x} "
            f"groups={len(groups)}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
