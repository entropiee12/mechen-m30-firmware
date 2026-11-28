#!/usr/bin/env bash
# Execute the real usound.drv MMIO word-port helper with guest RAM ports.
# This proves mode/gate control flow only; it is not a physical MMIO test.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/usound.drv}
CROSS=${CROSS:-mipsel-linux-gnu-}
QEMU=${QEMU:-qemu-system-mipsel}
PY=${PYTHON:-python3}
OUT=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-usound-port-qemu.XXXXXX")
trap 'rm -rf "$OUT"' EXIT

[ -f "$DRIVER" ] || { echo "missing usound driver: $DRIVER" >&2; exit 1; }
for tool in "${CROSS}as" "${CROSS}ld" "$QEMU" "$PY"; do
  command -v "$tool" >/dev/null || { echo "missing required tool: $tool" >&2; exit 1; }
done

"$PY" - "$DRIVER" "$OUT/usound_code.bin" <<'PY'
from pathlib import Path
import struct
import sys

source = Path(sys.argv[1]).read_bytes()
start, size = 0x200, 0x26B0
segment = bytearray(source[start : start + size])
if len(segment) != size:
    raise SystemExit("usound.drv resident segment is truncated")

# bfc3194c..bfc31954 are the exact recovered gate/data/alternate words.
for offset, value in {
    0x194C: 0x80060000,
    0x1950: 0x80060004,
    0x1954: 0x80060008,
}.items():
    struct.pack_into("<I", segment, offset, value)
Path(sys.argv[2]).write_bytes(segment)
PY

(
  cd "$OUT"
  "${CROSS}as" -EL -mabi=32 -mips32 -I "$OUT" \
    -o qemu_usound_port_probe.o "$ROOT/tools/emu/qemu_usound_port_probe.S"
  "${CROSS}ld" -EL -m elf32ltsmip \
    -T "$ROOT/tools/emu/qemu_usound_port_probe.ld" \
    -o qemu_usound_port_probe.elf qemu_usound_port_probe.o
)

"$PY" - "$QEMU" "$OUT/qemu_usound_port_probe.elf" <<'PY'
import subprocess
import sys

qemu, elf = sys.argv[1:]
command = [
    qemu, "-M", "malta", "-cpu", "24Kc", "-m", "16M", "-nographic",
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
    if "REAL USOUND PORT PASS" in output:
        raise SystemExit(0)
    raise SystemExit("QEMU port probe timed out before PASS")

print(result.stdout, end="")
if "REAL USOUND PORT PASS" not in result.stdout:
    raise SystemExit(f"QEMU port probe failed (exit={result.returncode})")
PY
