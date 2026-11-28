#!/usr/bin/env bash
# Execute the real M30 SET_INTERFACE handler on QEMU/Malta with RAM-backed MMIO.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/usound.drv}
CROSS=${CROSS:-mipsel-linux-gnu-}
QEMU=${QEMU:-qemu-system-mipsel}
PY=${PYTHON:-python3}
OUT=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-usound-ep0-qemu.XXXXXX")
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
segment = bytearray(source[start:start + size])
if len(segment) != size:
    raise SystemExit("usound.drv resident segment is truncated")

# Preserve direct MIPS16 JAL targets by moving the code to the same 0x8fc3
# region. Remap only the audited handler literal pool and ACK helper pointer.
for offset in range(0xC38, 0xCA9, 4):
    value, = struct.unpack_from("<I", segment, offset)
    if 0x9FC33C00 <= value <= 0x9FC33E14:
        value = 0x80060000 + value - 0x9FC33C00
    elif 0xC0080180 <= value <= 0xC0080186:
        value = 0x80063180 + value - 0xC0080180
    else:
        raise SystemExit(f"unexpected handler literal at {offset:#x}: {value:#010x}")
    struct.pack_into("<I", segment, offset, value)

ack_pointer, = struct.unpack_from("<I", segment, 0x18)
if ack_pointer != 0xC0080002:
    raise SystemExit(f"unexpected ACK pointer: {ack_pointer:#010x}")
struct.pack_into("<I", segment, 0x18, 0x80063002)
Path(sys.argv[2]).write_bytes(segment)
PY

(
  cd "$OUT"
  "${CROSS}as" -EL -mabi=32 -mips32 -I "$OUT" \
    -o qemu_usound_ep0_probe.o "$ROOT/tools/emu/qemu_usound_ep0_probe.S"
  "${CROSS}ld" -EL -m elf32ltsmip \
    -T "$ROOT/tools/emu/qemu_usound_ep0_probe.ld" \
    -o qemu_usound_ep0_probe.elf qemu_usound_ep0_probe.o
)

"$PY" - "$QEMU" "$OUT/qemu_usound_ep0_probe.elf" <<'PY'
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
    if "REAL USOUND EP0 PASS" in output:
        raise SystemExit(0)
    raise SystemExit("QEMU EP0 probe timed out before PASS")

print(result.stdout, end="")
if "REAL USOUND EP0 PASS" not in result.stdout:
    raise SystemExit(f"QEMU EP0 probe failed (exit={result.returncode})")
PY
