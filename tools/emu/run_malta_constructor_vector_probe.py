#!/usr/bin/env python3
"""Run the extracted kernel table constructor and exception vector on Malta."""

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
    candidate = override or shutil.which(name)
    if not candidate:
        raise SystemExit(f"required tool not found: {name}")
    return candidate


def reserve_qemu_log(path: Path) -> Path:
    log_path = path.expanduser().resolve(strict=False)
    allowed_roots = {Path(tempfile.gettempdir()).resolve()}
    private_tmp = Path("/private/tmp")
    if private_tmp.exists():
        allowed_roots.add(private_tmp.resolve())
    if not any(log_path.is_relative_to(root) for root in allowed_roots):
        raise SystemExit("--qemu-log must be a new file inside a temporary directory")
    if not log_path.parent.is_dir():
        raise SystemExit("--qemu-log parent directory must already exist")
    try:
        fd = os.open(log_path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError:
        raise SystemExit("--qemu-log refuses to overwrite an existing file")
    os.close(fd)
    return log_path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("kernel_driver", type=Path)
    parser.add_argument("kernel_init", type=Path)
    parser.add_argument("--qemu", help="QEMU executable (default: qemu-system-mipsel)")
    parser.add_argument("--cpu", default="24Kf", help="QEMU CPU model (default: 24Kf)")
    parser.add_argument("--qemu-log", type=Path, help="write QEMU instruction trace to this path")
    parser.add_argument("--as", dest="assembler", help="MIPS assembler override")
    parser.add_argument("--ld", dest="linker", help="MIPS linker override")
    parser.add_argument("--objcopy", help="MIPS objcopy override")
    parser.add_argument("--timeout", type=float, default=3.0)
    args = parser.parse_args()

    kernel = args.kernel_driver.resolve()
    init = args.kernel_init.resolve()
    if not kernel.is_file() or not init.is_file():
        raise SystemExit("kernel.drv and ker_init.bin must both exist")
    qemu_log = reserve_qemu_log(args.qemu_log) if args.qemu_log else None

    assembler = tool("mipsel-linux-gnu-as", args.assembler)
    linker = tool("mipsel-linux-gnu-ld", args.linker)
    objcopy = tool("mipsel-linux-gnu-objcopy", args.objcopy)
    qemu = tool("qemu-system-mipsel", args.qemu)

    with tempfile.TemporaryDirectory(prefix="m30-malta-ctor-vector-") as temp_name:
        temp = Path(temp_name)
        obj = temp / "probe.o"
        elf = temp / "probe.elf"
        harness = temp / "harness.bin"
        bios = temp / "m30-constructor-vector-bios.bin"
        commands = [
            [assembler, "-EL", "-march=24kc", "-mips32r2", "-o", str(obj),
             str(EMU / "malta_constructor_vector_probe.S")],
            [linker, "-EL", "-T", str(EMU / "malta_constructor_vector_probe.ld"),
             str(obj), "-o", str(elf)],
            [objcopy, "-O", "binary", str(elf), str(harness)],
            ["python3", str(EMU / "build_malta_constructor_probe_bios.py"),
             str(kernel), str(init), str(harness), str(bios)],
        ]
        for command in commands:
            try:
                result = subprocess.run(
                    command, text=True, capture_output=True, timeout=BUILD_TIMEOUT
                )
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

        command = [
            qemu,
            "-M", "malta",
            "-cpu", args.cpu,
            "-m", "256M",
            "-bios", str(bios),
            "-display", "none",
            "-serial", "null",
            "-serial", "null",
            "-serial", "stdio",
            "-monitor", "none",
            "-no-reboot",
            "-no-shutdown",
        ]
        if qemu_log:
            command.extend(["-d", "in_asm,cpu", "-D", str(qemu_log)])
        try:
            result = subprocess.run(
                command,
                text=True,
                capture_output=True,
                timeout=args.timeout,
                env={**os.environ, "QEMU_AUDIO_DRV": "none"},
            )
            stdout, stderr, timed_out = result.stdout, result.stderr, False
        except subprocess.TimeoutExpired as exc:
            stdout = exc.stdout or ""
            stderr = exc.stderr or ""
            timed_out = True
            if isinstance(stdout, bytes):
                stdout = stdout.decode(errors="replace")
            if isinstance(stderr, bytes):
                stderr = stderr.decode(errors="replace")

        if stdout.strip() != "PASS" or not timed_out:
            raise SystemExit(
                "QEMU did not halt after the constructor/vector PASS marker.\n"
                f"timed_out={timed_out}\nstdout:\n{stdout}\nstderr:\n{stderr}"
            )
        print("MALTA M30 CONSTRUCTOR+VECTOR PASS slots=0..13+sentinels EPC-RA-ExcCode=9")
        if stderr.strip():
            print(stderr.strip())


if __name__ == "__main__":
    main()
