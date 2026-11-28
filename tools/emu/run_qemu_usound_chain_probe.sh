#!/usr/bin/env bash
# Execute the real usound producer and ring consumer in one guest.
# The callback is trapped at the syscall envelope; PCM/DAC ownership is not claimed.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/usound.drv}
CROSS=${CROSS:-mipsel-linux-gnu-}
QEMU=${QEMU:-qemu-system-mipsel}
PY=${PYTHON:-python3}
OUT=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-usound-chain-qemu.XXXXXX")
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

stub = 0x80012001
relocations = {
    # Producer literals at bfc31024..bfc31068. The 0x6e40 producer ring
    # (bfc31058) is deliberately relocated onto the consumer ring below.
    0x1024: 0x80060000,
    0x1028: stub,
    0x102C: 0x80060004,
    0x1030: 0x80060008,
    0x1034: 0x80060009,
    0x1038: 0x80062000,
    0x103C: stub,
    0x1040: stub,
    0x1044: 0x80060010,
    0x104C: 0x80050CE9,
    0x1050: 0x80050CAD,
    0x1054: 0x80060018,
    0x1058: 0x80061000,
    0x105C: 0x80060020,
    0x1060: 0x80060024,
    0x1064: 0x80060028,
    0x1068: stub,
    # Consumer literals at bfc31728..bfc3173c. Its 0x1000 ring aliases the
    # producer ring at 0x80061000 in this synthetic composition probe.
    0x1728: 0x80063000,
    0x172C: 0x80063004,
    0x1730: 0x80063008,
    0x1734: 0x80061000,
    0x1738: 0x800526A9,
    0x173C: 0x8006300C,
}
for offset, value in relocations.items():
    struct.pack_into("<I", segment, offset, value)
Path(sys.argv[2]).write_bytes(segment)
PY

(
  cd "$OUT"
  "${CROSS}as" -EL -mabi=32 -mips32 -I "$OUT" \
    -o qemu_usound_chain_probe.o "$ROOT/tools/emu/qemu_usound_chain_probe.S"
  "${CROSS}ld" -EL -m elf32ltsmip \
    -T "$ROOT/tools/emu/qemu_usound_chain_probe.ld" \
    -o qemu_usound_chain_probe.elf qemu_usound_chain_probe.o
)

"$PY" - "$QEMU" "$OUT/qemu_usound_chain_probe.elf" <<'PY'
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
    if "REAL USOUND CHAIN PASS" in output:
        raise SystemExit(0)
    if "REAL USOUND CHAIN FAIL" in output:
        raise SystemExit("QEMU usound chain probe reported FAIL")
    raise SystemExit("QEMU usound chain probe timed out before PASS")

print(result.stdout, end="")
if "REAL USOUND CHAIN PASS" not in result.stdout:
    raise SystemExit(f"QEMU usound chain probe failed (exit={result.returncode})")
PY
