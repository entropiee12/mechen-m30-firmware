#!/usr/bin/env python3
"""Run one QEMU command with a hard timeout and wait for its process."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--timeout", type=float, required=True)
    parser.add_argument("--stdout", type=Path, required=True)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if args.timeout <= 0:
        parser.error("--timeout must be greater than zero")
    if not command:
        parser.error("a QEMU command is required after --")

    try:
        with args.stdout.open("wb") as output:
            result = subprocess.run(
                command, stdout=output, timeout=args.timeout, check=False
            )
    except subprocess.TimeoutExpired:
        print(
            f"QEMU timed out after {args.timeout:g}s; child terminated and waited",
            file=sys.stderr,
        )
        return 124
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
