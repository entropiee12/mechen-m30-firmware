#!/usr/bin/env bash
# Execute real drv_dac.drv byte-transfer helpers with synthetic RAM GPIO.
# This proves the driver boundary only; it does not model a codec or audio DAC.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/drv_dac.drv}
CROSS=${CROSS:-mipsel-linux-gnu-}
QEMU=${QEMU:-qemu-system-mipsel}
PY=${PYTHON:-python3}
OUT=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-dac-qemu.XXXXXX")
trap 'rm -rf "$OUT"' EXIT

[ -f "$DRIVER" ] || { echo "missing DAC driver: $DRIVER" >&2; exit 1; }
for tool in "${CROSS}as" "${CROSS}ld" "$QEMU" "$PY"; do
  command -v "$tool" >/dev/null || { echo "missing required tool: $tool" >&2; exit 1; }
done

"$PY" - "$DRIVER" "$OUT/dac_code.bin" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1]).read_bytes()
start, size = 0x200, 0x800
segment = source[start : start + size]
if len(segment) != size:
    raise SystemExit("drv_dac.drv resident segment is truncated")
Path(sys.argv[2]).write_bytes(segment)
PY

(
  cd "$OUT"
  "${CROSS}as" -EL -mabi=32 -mips32 -I "$OUT" \
    -o qemu_dac_probe.o "$ROOT/tools/emu/qemu_dac_probe.S"
  "${CROSS}ld" -EL -m elf32ltsmip \
    -T "$ROOT/tools/emu/qemu_dac_probe.ld" \
    -o qemu_dac_probe.elf qemu_dac_probe.o
)

"$PY" - "$QEMU" "$OUT/qemu_dac_probe.elf" <<'PY'
import subprocess
import sys

qemu, elf = sys.argv[1:]
command = [
    qemu, "-M", "malta", "-cpu", "24Kc", "-m", "256M", "-nographic",
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
    if "REAL DAC QEMU PASS" in output:
        raise SystemExit(0)
    if "REAL DAC QEMU FAIL" in output:
        raise SystemExit("QEMU DAC probe reported FAIL")
    raise SystemExit("QEMU DAC probe timed out before PASS")

print(result.stdout, end="")
if "REAL DAC QEMU PASS" not in result.stdout:
    raise SystemExit(f"QEMU DAC probe failed (exit={result.returncode})")
PY
