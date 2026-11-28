#!/usr/bin/env python3
"""Check whether extracted kernel-support members contain MIPS16 break sites."""

from __future__ import annotations

import argparse
import shutil
import subprocess
from pathlib import Path


MEMBERS = (
    ("ker_text.bin", 0xBFC30000),
    ("ker_init.bin", 0xBFC32000),
    ("kernel.drv", 0xBFC08000),
)


def count_breaks(objdump: str, path: Path, base: int) -> int:
    output = subprocess.check_output(
        [
            objdump,
            "-D",
            "-b",
            "binary",
            "-m",
            "mips:16",
            "-EL",
            f"--adjust-vma={base:#x}",
            str(path),
        ],
        text=True,
        stderr=subprocess.STDOUT,
    )
    return sum("\tbreak" in line.lower() for line in output.splitlines())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path, help="extracted firmware directory")
    parser.add_argument("--objdump", default="mipsel-linux-gnu-objdump")
    args = parser.parse_args()
    if shutil.which(args.objdump) is None and not Path(args.objdump).is_file():
        raise SystemExit(f"missing objdump: {args.objdump}")

    total = 0
    for name, base in MEMBERS:
        path = args.root / name
        if not path.is_file():
            raise SystemExit(f"missing support member: {path}")
        count = count_breaks(args.objdump, path, base)
        total += count
        print(f"SUPPORT_BREAK file={name} base=0x{base:08x} count={count}")

    if total:
        raise SystemExit(f"unexpected extracted-kernel MIPS16 break count: {total}")
    print("KERNEL SYSCALL SCOPE PASS support_mips16_break=0 fixed_exception=0xbfc06d6c")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
