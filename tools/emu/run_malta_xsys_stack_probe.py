#!/usr/bin/env python3
"""Run the hash-pinned M30 alternate-stack selector on QEMU Malta."""

from __future__ import annotations

import argparse
import os
import shlex
import shutil
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
EMU = ROOT / "tools" / "emu"
BUILD_TIMEOUT = 30


def tool(name: str, override: str | None = None) -> str:
    result = override or shutil.which(name)
    if not result:
        raise SystemExit(f"required tool not found: {name}")
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("ker_xsys", type=Path)
    parser.add_argument("--qemu", help="QEMU executable (default: qemu-system-mipsel)")
    parser.add_argument("--cpu", action="append", dest="cpus",
                        help="CPU model (repeatable; defaults to 24Kf and 34Kf)")
    parser.add_argument("--as", dest="assembler")
    parser.add_argument("--ld", dest="linker")
    parser.add_argument("--objcopy")
    parser.add_argument("--timeout", type=float, default=2.0)
    args = parser.parse_args()

    xsys = args.ker_xsys.resolve()
    if not xsys.is_file():
        raise SystemExit(f"missing ker_xsys.bin: {xsys}")
    assembler = tool("mipsel-linux-gnu-as", args.assembler)
    linker = tool("mipsel-linux-gnu-ld", args.linker)
    objcopy = tool("mipsel-linux-gnu-objcopy", args.objcopy)
    qemu = tool("qemu-system-mipsel", args.qemu)
    cpus = args.cpus or ["24Kf", "34Kf"]

    with tempfile.TemporaryDirectory(prefix="m30-malta-xsys-stack-") as temp_name:
        temp = Path(temp_name)
        obj = temp / "probe.o"
        elf = temp / "probe.elf"
        harness = temp / "harness.bin"
        bios = temp / "m30-xsys-stack-bios.bin"
        commands = (
            [assembler, "-EL", "-march=24kc", "-mips32r2", "-o", str(obj),
             str(EMU / "malta_xsys_stack_probe.S")],
            [linker, "-EL", "-T", str(EMU / "malta_xsys_stack_probe.ld"),
             str(obj), "-o", str(elf)],
            [objcopy, "-O", "binary", str(elf), str(harness)],
            ["python3", str(EMU / "build_malta_xsys_stack_probe_bios.py"),
             str(xsys), str(harness), str(bios)],
        )
        for command in commands:
            try:
                result = subprocess.run(command, text=True, capture_output=True,
                                        timeout=BUILD_TIMEOUT)
            except subprocess.TimeoutExpired:
                raise SystemExit(
                    f"build command exceeded {BUILD_TIMEOUT}s: {shlex.join(command)}"
                )
            if result.returncode:
                raise SystemExit(
                    f"command failed: {shlex.join(command)}\n"
                    f"{result.stdout}{result.stderr}"
                )
            if result.stdout:
                print(result.stdout, end="")
            if result.stderr:
                print(result.stderr, end="")

        for cpu in cpus:
            command = [
                qemu, "-M", "malta", "-cpu", cpu, "-m", "256M",
                "-bios", str(bios), "-display", "none", "-serial", "null",
                "-serial", "null", "-serial", "stdio", "-monitor", "none",
                "-no-reboot", "-no-shutdown",
            ]
            try:
                result = subprocess.run(
                    command, text=True, capture_output=True, timeout=args.timeout,
                    env={**os.environ, "QEMU_AUDIO_DRV": "none"},
                )
                stdout, stderr, timed_out = result.stdout, result.stderr, False
            except subprocess.TimeoutExpired as exc:
                stdout, stderr, timed_out = exc.stdout or "", exc.stderr or "", True
                if isinstance(stdout, bytes):
                    stdout = stdout.decode(errors="replace")
                if isinstance(stderr, bytes):
                    stderr = stderr.decode(errors="replace")
            if stdout.strip() != "PASS" or not timed_out:
                raise SystemExit(
                    f"QEMU {cpu} did not halt after PASS. timed_out={timed_out}\n"
                    f"stdout:\n{stdout}\nstderr:\n{stderr}"
                )
            if "FAIL" in stdout or "qemu: fatal:" in stderr:
                raise SystemExit(f"QEMU {cpu} failed:\n{stdout}\n{stderr}")
            print(
                f"MALTA XSYS STACK PASS cpu={cpu} "
                "index0=below/equal:alternate,above:direct index1:direct "
                "exception-entry=EPC/RA-frame"
            )


if __name__ == "__main__":
    main()
