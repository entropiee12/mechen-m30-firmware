#!/usr/bin/env bash
# Execute dependency-free functions from the real usound.drv under QEMU.
# This is not a full driver boot: only the copied leaf code and guest RAM run.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/usound.drv}
CROSS=${CROSS:-mipsel-linux-gnu-}
QEMU=${QEMU:-qemu-system-mipsel}
PY=${PYTHON:-python3}
OUT=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-usound-leaf-qemu.XXXXXX")
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
  "${CROSS}as" -EL -mabi=32 -mips32 \
    -I "$OUT" -o qemu_usound_leaf_probe.o \
    "$ROOT/tools/emu/qemu_usound_leaf_probe.S"
  "${CROSS}ld" -EL -m elf32ltsmip \
    -T "$ROOT/tools/emu/qemu_usound_leaf_probe.ld" \
    -o qemu_usound_leaf_probe.elf qemu_usound_leaf_probe.o
)

"$PY" - "$QEMU" "$OUT/qemu_usound_leaf_probe.elf" <<'PY'
import subprocess
import sys

qemu, elf = sys.argv[1:]
command = [
    qemu,
    "-M", "malta",
    "-cpu", "24Kc",
    "-m", "16M",
    "-nographic",
    "-monitor", "none",
    # Malta's FPGA UART is the third serial backend.
    "-serial", "null",
    "-serial", "null",
    "-serial", "stdio",
    "-kernel", elf,
]
try:
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, timeout=8)
except subprocess.TimeoutExpired as exc:
    output = exc.stdout or ""
    if isinstance(output, bytes):
        output = output.decode(errors="replace")
    print(output, end="")
    if "REAL USOUND MIPS16 LEAF PASS" in output:
        raise SystemExit(0)
    raise SystemExit("QEMU leaf probe timed out before PASS")

print(result.stdout, end="")
if "REAL USOUND MIPS16 LEAF PASS" not in result.stdout:
    raise SystemExit(f"QEMU leaf probe failed (exit={result.returncode})")
PY
