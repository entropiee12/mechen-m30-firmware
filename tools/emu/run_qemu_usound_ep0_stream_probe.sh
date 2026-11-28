#!/usr/bin/env bash
# Execute SET_INTERFACE followed by the real producer against shared guest state.
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
import hashlib
import struct
import sys

source_path = Path(sys.argv[1])
source = source_path.read_bytes()
expected_sha256 = "e593bdd29c2a96d4d745cf1b5cae4da2ee71c2211c551e415209b18f54e600fc"
actual_sha256 = hashlib.sha256(source).hexdigest()
if actual_sha256 != expected_sha256:
    raise SystemExit(f"unexpected usound.drv SHA-256: {actual_sha256}")
start, size = 0x200, 0x26B0
segment = bytearray(source[start:start + size])
if len(segment) != size:
    raise SystemExit("usound.drv resident segment is truncated")

# Preserve direct MIPS16 JAL targets by moving the code to the same 0x8fc3
# region. Remap the audited handler/producer literals to one shared state map.
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

callback = 0x80012001
producer_expected = {
    0x1024: 0xC008000B,
    0x1028: 0xBFC30D9D,
    0x102C: 0x9FC33DD8,
    0x1030: 0xC0080008,
    0x1034: 0xC0080009,
    0x1038: 0x9FC34000,
    0x103C: 0xBFC326A9,
    0x1040: 0xBFC318CD,
    0x1044: 0x9FC33E20,
    0x104C: 0xBFC30CE9,
    0x1050: 0xBFC30CAD,
    0x1054: 0x9FC33DE8,
    0x1058: 0x9FC24000,
    0x105C: 0x9FC33DF0,
    0x1060: 0x9FC33E13,
    0x1064: 0x9FC33DF8,
    0x1068: 0xBFC30D69,
    0x12D0: 0xC00C0004,
    0x12D4: 0x9FC33DF0,
    0x12D8: 0x9FC33E13,
    0x12DC: 0xC0000110,
    0x136C: 0xC0000000,
    0x1370: 0xC0001004,
    0x1374: 0xC00C0040,
    0x1378: 0xC00C0004,
    0x137C: 0xC00C0008,
    0x1380: 0xC00C0044,
    0x1384: 0x9FC24000,
    0x1388: 0x9FC33E20,
    0x138C: 0xC00C0054,
    0x14B8: 0xC0000110,
    0x14C0: 0xC000100C,
    0x14C4: 0xC0050024,
    0x14C8: 0xC0000000,
    0x14CC: 0xC0050000,
    0x14D0: 0xC005001C,
    0x14D4: 0xC0050004,
    0x14D8: 0xC0050014,
    0x14DC: 0xC0050018,
}
for offset, expected in producer_expected.items():
    actual, = struct.unpack_from("<I", segment, offset)
    if actual != expected:
        raise SystemExit(
            f"unexpected producer literal at {offset:#x}: "
            f"{actual:#010x} != {expected:#010x}"
        )

producer_literals = {
    0x1024: 0x80060000,  # status flag
    0x1028: callback,    # poll callback
    0x102C: 0x80060004,  # stop flag
    0x1030: 0x80060008,  # USB length low byte
    0x1034: 0x80060009,  # USB length high byte
    0x1038: 0x80062000,  # staging buffer
    0x103C: callback,    # staging fill callback
    0x1040: callback,    # word-port callback
    0x1044: 0x80067020,  # format flag, separate from staging buffer
    0x104C: 0x8FC30CE9,  # real 3-to-4 MIPS16 helper
    0x1050: 0x8FC30CAD,  # real byte-copy MIPS16 helper
    0x1054: 0x800601E8,  # same write offset cleared by SET_INTERFACE
    0x1058: 0x80080000,  # software ring, isolated from staging/MMIO regions
    0x105C: 0x800601F0,  # same queued-byte count cleared by SET_INTERFACE
    0x1060: 0x80060213,  # same ready state cleared by SET_INTERFACE
    0x1064: 0x80060028,  # producer reset flag
    0x1068: callback,    # reset callback
    0x136C: 0x80064000,  # DMA control 0
    0x1370: 0x80065004,  # DMA control 1
    0x1374: 0x80064040,  # channel control
    0x1378: 0x80064004,  # common control 0
    0x137C: 0x80064008,  # common control 1
    0x1380: 0x80064044,  # DMA ring address
    0x1384: 0x80080000,  # same ring written by producer
    0x1388: 0x80067020,  # same format flag used by producer
    0x138C: 0x80064054,  # DMA size
    # FUN_bfc31390's helper MMIO pool, modeled as ordinary guest RAM.
    0x14B8: 0x80064010,
    0x14C0: 0x8006420C,
    0x14C4: 0x80064324,
    0x14C8: 0x80064000,
    0x14CC: 0x80064300,
    0x14D0: 0x8006431C,
    0x14D4: 0x80064304,
    0x14D8: 0x80064314,
    0x14DC: 0x80064318,
    # FUN_bfc3125c completion state shares the producer's RAM aliases.
    0x12D0: 0x80064004,
    0x12D4: 0x800601F0,
    0x12D8: 0x80060213,
    0x12DC: 0x80064010,
}
for offset, value in producer_literals.items():
    struct.pack_into("<I", segment, offset, value)

Path(sys.argv[2]).write_bytes(segment)
PY

(
  cd "$OUT"
  "${CROSS}as" -EL -mabi=32 -mips32 -I "$OUT" \
    -o qemu_usound_ep0_stream_probe.o "$ROOT/tools/emu/qemu_usound_ep0_stream_probe.S"
  "${CROSS}ld" -EL -m elf32ltsmip \
    -T "$ROOT/tools/emu/qemu_usound_ep0_stream_probe.ld" \
    -o qemu_usound_ep0_stream_probe.elf qemu_usound_ep0_stream_probe.o
)

"$PY" - "$QEMU" "$OUT/qemu_usound_ep0_stream_probe.elf" <<'PY'
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
    if "REAL USOUND EP0 STREAM PASS" in output:
        raise SystemExit(0)
    raise SystemExit("QEMU EP0 probe timed out before PASS")

print(result.stdout, end="")
if "REAL USOUND EP0 STREAM PASS" not in result.stdout:
    raise SystemExit(f"QEMU EP0 probe failed (exit={result.returncode})")
if result.returncode != 0:
    raise SystemExit(f"QEMU EP0 probe exited abnormally after PASS (exit={result.returncode})")
PY
