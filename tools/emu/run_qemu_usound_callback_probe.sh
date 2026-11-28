#!/usr/bin/env bash
# Execute the real bfc326a9 MIPS16 break-0 callback under QEMU.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/usound.drv}
CROSS=${CROSS:-mipsel-linux-gnu-}
QEMU=${QEMU:-qemu-system-mipsel}
PY=${PYTHON:-python3}
OUT=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-usound-callback-qemu.XXXXXX")
trap 'rm -rf "$OUT"' EXIT

[ -f "$DRIVER" ] || { echo "missing usound driver: $DRIVER" >&2; exit 1; }
for tool in "${CROSS}as" "${CROSS}ld" "$QEMU" "$PY"; do
  command -v "$tool" >/dev/null || { echo "missing required tool: $tool" >&2; exit 1; }
done

"$PY" - "$DRIVER" "$OUT/usound_code.bin" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1]).read_bytes()
start, size = 0x200, 0x26B0
segment = source[start : start + size]
if len(segment) != size:
    raise SystemExit("usound.drv resident segment is truncated")
Path(sys.argv[2]).write_bytes(segment)
PY

(
  cd "$OUT"
  "${CROSS}as" -EL -mabi=32 -mips32 -I "$OUT" \
    -o qemu_usound_callback_probe.o "$ROOT/tools/emu/qemu_usound_callback_probe.S"
  "${CROSS}ld" -EL -m elf32ltsmip \
    -T "$ROOT/tools/emu/qemu_usound_callback_probe.ld" \
    -o qemu_usound_callback_probe.elf qemu_usound_callback_probe.o
)

"$PY" - "$QEMU" "$OUT/qemu_usound_callback_probe.elf" <<'PY'
import subprocess
import os
import sys

qemu, elf = sys.argv[1:]
command = [
    qemu,
]
plugin = os.environ.get("QEMU_PLUGIN")
if plugin:
    command += ["-plugin", plugin]
command += [
    "-M", "malta", "-cpu", "24Kc", "-m", "16M", "-nographic",
    "-monitor", "none", "-serial", "null", "-serial", "null",
    "-serial", "stdio", "-kernel", elf,
]
try:
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, timeout=8)
except subprocess.TimeoutExpired as exc:
    output = exc.stdout or ""
    if isinstance(output, bytes):
        output = output.decode(errors="replace")
    print(output, end="")
    if "REAL USOUND MODULE-CALL PASS" in output:
        raise SystemExit(0)
    raise SystemExit("QEMU module-call probe timed out before PASS")

print(result.stdout, end="")
if "REAL USOUND MODULE-CALL PASS" not in result.stdout:
    raise SystemExit(f"QEMU module-call probe failed (exit={result.returncode})")
PY
