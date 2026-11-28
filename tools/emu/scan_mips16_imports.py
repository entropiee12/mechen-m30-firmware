#!/usr/bin/env python3
"""Find MIPS16 indirect calls whose delay slot selects an import.

The M30 UI bank uses the MIPS16 pattern::

    jalr    s0
    li      a3, 15

The immediate is in the delay slot, so a raw byte search is not sufficient.
This tool delegates decoding to the installed MIPS binutils and only reports
adjacent decoded instructions. It never executes a firmware payload.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence


INSTRUCTION_RE = re.compile(
    r"^\s*([0-9a-fA-F]+):\s+(?:(?:[0-9a-fA-F]{4})\s+)+(.+?)\s*$"
)
JALR_MNEMONICS = {"jalr", "jalrc"}
LI_RE = re.compile(r"^li\s+a3\s*,\s*(0x[0-9a-f]+|[0-9]+)\s*$", re.IGNORECASE)


@dataclass(frozen=True)
class Instruction:
    address: int
    text: str


@dataclass(frozen=True)
class ImportCall:
    call_address: int
    selector_address: int
    selector: int
    call_text: str
    selector_text: str


def parse_disassembly(text: str) -> tuple[Instruction, ...]:
    """Parse objdump's MIPS16 instruction lines, ignoring data/ellipsis."""

    instructions: list[Instruction] = []
    for line in text.splitlines():
        match = INSTRUCTION_RE.match(line)
        if match is None:
            continue
        instructions.append(Instruction(int(match.group(1), 16), match.group(2).strip()))
    return tuple(instructions)


def find_import_calls(
    instructions: Iterable[Instruction],
    *,
    selectors: set[int] | None = None,
) -> tuple[ImportCall, ...]:
    """Return indirect calls followed by ``li a3,<selector>``."""

    items = tuple(instructions)
    found: list[ImportCall] = []
    for call, delay in zip(items, items[1:]):
        call_mnemonic = call.text.split(None, 1)[0].lower()
        if call_mnemonic not in JALR_MNEMONICS:
            continue
        match = LI_RE.match(delay.text)
        if match is None:
            continue
        selector = int(match.group(1), 0)
        if selectors is not None and selector not in selectors:
            continue
        found.append(ImportCall(call.address, delay.address, selector, call.text, delay.text))
    return tuple(found)


def run_objdump(
    objdump: str,
    firmware: Path,
    *,
    base: int,
    offset: int,
    size: int,
) -> str:
    start = base + offset
    stop = start + size
    command = [
        objdump,
        "-D",
        "-b",
        "binary",
        "-m",
        "mips:16",
        "-EL",
        f"--adjust-vma={base:#x}",
        f"--start-address={start:#x}",
        f"--stop-address={stop:#x}",
        str(firmware),
    ]
    return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT)


def parse_int(value: str) -> int:
    return int(value, 0)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("firmware", type=Path)
    parser.add_argument("--objdump", default=os.environ.get("OBJDUMP", "mipsel-linux-gnu-objdump"))
    parser.add_argument("--base", type=parse_int, default=0x260DB980)
    parser.add_argument("--offset", type=parse_int, default=0x9674)
    parser.add_argument("--size", type=parse_int, default=0x80C)
    parser.add_argument(
        "--require-selector",
        action="append",
        type=parse_int,
        default=[],
        help="fail unless this selector is observed at least once",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if not args.firmware.is_file():
        raise SystemExit(f"missing firmware: {args.firmware}")
    if shutil.which(args.objdump) is None and not Path(args.objdump).is_file():
        raise SystemExit(f"missing objdump: {args.objdump}")

    output = run_objdump(
        args.objdump,
        args.firmware,
        base=args.base,
        offset=args.offset,
        size=args.size,
    )
    calls = find_import_calls(parse_disassembly(output))
    for call in calls:
        print(
            "MIPS16 IMPORT "
            f"call=0x{call.call_address:08x} "
            f"selector=0x{call.selector:x} "
            f"delay=0x{call.selector_address:08x}"
        )

    counts: dict[int, int] = {}
    for call in calls:
        counts[call.selector] = counts.get(call.selector, 0) + 1
    summary = ",".join(f"{selector}:{counts[selector]}" for selector in sorted(counts)) or "none"
    print(f"MIPS16 IMPORT SUMMARY calls={len(calls)} selectors={summary}")

    missing = sorted(set(args.require_selector) - set(counts))
    if missing:
        print(
            "missing required selectors: " + ", ".join(f"0x{selector:x}" for selector in missing),
            file=os.sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
