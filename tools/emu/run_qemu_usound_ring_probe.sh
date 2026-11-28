#!/usr/bin/env bash
# Execute the real usound.drv ring consumer with its real break-0 callback.
# The guest catches the syscall envelope; it does not emulate the kernel
# service or claim PCM/DAC ownership.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/usound.drv}
CROSS=${CROSS:-mipsel-linux-gnu-}
QEMU=${QEMU:-qemu-system-mipsel}
PY=${PYTHON:-python3}
OUT=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-usound-ring-qemu.XXXXXX")
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

# These are the exact literal-pool words recovered around bfc31728. Only the
# state locations and callback are relocated; driver instructions stay byte
# identical to the source segment.
relocations = {
    0x1728: 0x80060000,  # available bytes
    0x172C: 0x80060004,  # short-read count
    0x1730: 0x80060008,  # read offset
    0x1734: 0x80061000,  # 0x1000-byte ring
    0x1738: 0x800526A9,  # real bfc326a9 MIPS16 callback
    0x173C: 0x8006000C,  # reset signal
}
for offset, value in relocations.items():
    struct.pack_into("<I", segment, offset, value)
Path(sys.argv[2]).write_bytes(segment)
PY

(
  cd "$OUT"
  "${CROSS}as" -EL -mabi=32 -mips32 -I "$OUT" \
    -o qemu_usound_ring_probe.o "$ROOT/tools/emu/qemu_usound_ring_probe.S"
  "${CROSS}ld" -EL -m elf32ltsmip \
    -T "$ROOT/tools/emu/qemu_usound_ring_probe.ld" \
    -o qemu_usound_ring_probe.elf qemu_usound_ring_probe.o
)

"$PY" - "$QEMU" "$OUT/qemu_usound_ring_probe.elf" <<'PY'
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
    if "REAL USOUND RING PASS" in output:
        raise SystemExit(0)
    raise SystemExit("QEMU ring probe timed out before PASS")

print(result.stdout, end="")
if "REAL USOUND RING PASS" not in result.stdout:
    raise SystemExit(f"QEMU ring probe failed (exit={result.returncode})")
PY
