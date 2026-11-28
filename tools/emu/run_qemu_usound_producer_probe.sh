#!/usr/bin/env bash
# Execute real usound producer, DMA setup, and completion code under QEMU.
# Hardware registers are RAM-backed; external USB and stop-helper effects are shimmed.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/usound.drv}
CROSS=${CROSS:-mipsel-linux-gnu-}
QEMU=${QEMU:-qemu-system-mipsel}
PY=${PYTHON:-python3}
OUT=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-usound-producer-qemu.XXXXXX")
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
    0x1024: 0x80060000,  # status flag
    0x1028: stub,        # poll callback
    0x102C: 0x80060004,  # stop flag
    0x1030: 0x80060008,  # low available byte
    0x1034: 0x80060009,  # high available byte
    0x1038: 0x80062000,  # scratch block
    0x103C: stub,        # scratch fill callback
    0x1040: stub,        # word-port callback
    0x1044: 0x80062020,  # shared producer/DMA format flag
    # 0x1048 is the firmware's literal 0xaaaaaaab scale, not a pointer.
    0x104C: 0x80050CE9,  # real MIPS16 3-to-4 leaf
    0x1050: 0x80050CAD,  # real MIPS16 direct-copy leaf
    0x1054: 0x80060018,  # write offset
    0x1058: 0x80061000,  # 0x6e40-byte software ring
    0x105C: 0x80060020,  # available bytes
    0x1060: 0x80060024,  # ready signal
    0x1064: 0x80060028,  # reset flag
    0x1068: stub,         # reset callback
    0x136C: 0x80064000,  # DMA control 0
    0x1370: 0x80065004,  # DMA control 1
    0x1374: 0x80064040,  # channel control
    0x1378: 0x80064004,  # common control 0
    0x137C: 0x80064008,  # common control 1
    0x1380: 0x80064044,  # DMA ring address
    0x1384: 0x80061000,  # DMA ring in guest RAM
    0x1388: 0x80062020,  # format flag
    0x138C: 0x80064054,  # DMA size
    0x12D0: 0x80064004,  # completion DMA control
    0x12D4: 0x8006F000,  # available byte count
    0x12D8: 0x8006F004,  # ready latch
    0x12DC: 0x80064010,  # clock control
}
for offset, value in relocations.items():
    struct.pack_into("<I", segment, offset, value)
# Empty completion branch enters this MIPS16 helper. The counter-and-return stub
# isolates FUN_bfc3125c state effects; helper shutdown side effects are not run.
segment[0x14E0:0x14F0] = bytes.fromhex("03 b2 60 9a 01 4b 20 e8 60 da 00 65 30 f0 06 80")  # increment helper-call counter, then return
segment[0x1282:0x1286] = bytes.fromhex("20 18 38 45")  # bfc31282 JAL retargeted to guest FUN_bfc314e0 at 0x800514e0
Path(sys.argv[2]).write_bytes(segment)
PY

(
  cd "$OUT"
  "${CROSS}as" -EL -mabi=32 -mips32 -I "$OUT" \
    -o qemu_usound_producer_probe.o "$ROOT/tools/emu/qemu_usound_producer_probe.S"
  "${CROSS}ld" -EL -m elf32ltsmip \
    -T "$ROOT/tools/emu/qemu_usound_producer_probe.ld" \
    -o qemu_usound_producer_probe.elf qemu_usound_producer_probe.o
)

"$PY" - "$QEMU" "$OUT/qemu_usound_producer_probe.elf" <<'PY'
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
    if "REAL USOUND PRODUCER PASS" in output:
        raise SystemExit(0)
    raise SystemExit("QEMU producer probe timed out before PASS")

print(result.stdout, end="")
if "REAL USOUND PRODUCER PASS" not in result.stdout:
    raise SystemExit(f"QEMU producer probe failed (exit={result.returncode})")
PY
