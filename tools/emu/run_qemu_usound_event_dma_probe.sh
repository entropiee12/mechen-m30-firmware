#!/usr/bin/env bash
# Execute the observed usound event callback through real producer/DMA code.
# MMIO/DMA registers and external FIFO/helper behavior are synthetic RAM/shims.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/usound.drv}
CROSS=${CROSS:-mipsel-linux-gnu-}
QEMU=${QEMU:-qemu-system-mipsel}
PY=${PYTHON:-python3}
EXPECTED_SHA256=e593bdd29c2a96d4d745cf1b5cae4da2ee71c2211c551e415209b18f54e600fc
OUT=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-usound-event-dma-qemu.XXXXXX")
trap 'rm -rf "$OUT"' EXIT

[ -f "$DRIVER" ] || { echo "missing usound driver: $DRIVER" >&2; exit 1; }
for tool in "${CROSS}as" "${CROSS}ld" "$QEMU" "$PY" shasum; do
  command -v "$tool" >/dev/null || { echo "missing required tool: $tool" >&2; exit 1; }
done

ACTUAL_SHA256=$(shasum -a 256 "$DRIVER" | awk '{print $1}')
[ "$ACTUAL_SHA256" = "$EXPECTED_SHA256" ] || {
  echo "unexpected usound.drv SHA-256: $ACTUAL_SHA256" >&2
  exit 1
}

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
    0x1024: 0x80060000,  # producer status flag
    0x1028: stub,        # poll callback
    0x102C: 0x80060004,  # producer stop flag
    0x1030: 0x80060008,  # USB packet length low byte
    0x1034: 0x80060009,  # USB packet length high byte
    0x1038: 0x80068000,  # staging area; consumer ring shares this address
    0x103C: stub,        # scratch fill callback
    0x1040: stub,        # USB word-port callback
    0x1044: 0x80062020,  # shared format flag
    0x104C: 0x8FC30CE9,  # real MIPS16 3-to-4 helper
    0x1050: 0x8FC30CAD,  # real MIPS16 direct-copy helper
    0x1054: 0x80060018,  # producer write offset
    0x1058: 0x80061000,  # DMA ring; distinct from 0x9fc34000 staging/consumer area
    0x105C: 0x80060020,  # queued byte count
    0x1060: 0x80060024,  # ready signal
    0x1064: 0x80060028,  # producer reset flag
    0x1068: stub,        # reset callback
    # FUN_bfc31114 callback MMIO literals.
    0x1190: 0x80066000,  # observed event/status byte (bit 0x02)
    0x1194: 0x80066004,  # observed event acknowledge/mask register
    0x1198: 0x80066008,  # secondary status register
    0x119C: stub,        # alternate callback target bfc303c4
    # DMA control structure; these aliases are ordinary guest RAM.
    0x136C: 0x80064000,
    0x1370: 0x80065004,
    0x1374: 0x80064040,
    0x1378: 0x80064004,
    0x137C: 0x80064008,
    0x1380: 0x80064044,
    0x1384: 0x80061000,
    0x1388: 0x80062020,
    0x138C: 0x80064054,
    # Additional pointer pool used by FUN_bfc31390 at the threshold edge.
    0x14B8: 0x80064010,
    0x14C0: 0x8006420C,
    0x14C4: 0x80064324,
    0x14C8: 0x80064000,
    0x14CC: 0x80064300,
    0x14D0: 0x8006431C,
    0x14D4: 0x80064304,
    0x14D8: 0x80064314,
    0x14DC: 0x80064318,
    0x12D0: 0x80064004,
    0x12D4: 0x8006F000,
    0x12D8: 0x8006F004,
    0x12DC: 0x80064010,
}
for offset, value in relocations.items():
    struct.pack_into("<I", segment, offset, value)

Path(sys.argv[2]).write_bytes(segment)
PY

(
  cd "$OUT"
  "${CROSS}as" -EL -mabi=32 -mips32 \
    -o qemu_usound_event_dma_probe.o "$ROOT/tools/emu/qemu_usound_event_dma_probe.S"
  "${CROSS}ld" -EL -m elf32ltsmip \
    -T "$ROOT/tools/emu/qemu_usound_event_dma_probe.ld" \
    -o qemu_usound_event_dma_probe.elf qemu_usound_event_dma_probe.o
)

"$PY" - "$QEMU" "$OUT/qemu_usound_event_dma_probe.elf" <<'PY'
import os
import selectors
import subprocess
import sys
import time

qemu, elf = sys.argv[1:]
command = [
    qemu, "-M", "malta", "-cpu", "24Kc", "-m", "256M", "-nographic",
    "-monitor", "none", "-serial", "null", "-serial", "null",
    "-serial", "stdio", "-kernel", elf,
]
trace = os.environ.get("QEMU_TRACE")
if trace:
    command[1:1] = ["-d", "in_asm", "-D", trace]
try:
    process = subprocess.Popen(command, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT)
except OSError as exc:
    raise SystemExit(f"could not start QEMU: {exc}")

output = bytearray()
deadline = time.monotonic() + float(os.environ.get("QEMU_TIMEOUT", "12"))
selector = selectors.DefaultSelector()
selector.register(process.stdout, selectors.EVENT_READ)
marker = b"REAL USOUND EVENT-DMA PASS"
failure = b"REAL USOUND EVENT-DMA FAIL"
try:
    while process.poll() is None and time.monotonic() < deadline:
        events = selector.select(max(0.0, deadline - time.monotonic()))
        if not events:
            break
        chunk = os.read(process.stdout.fileno(), 4096)
        if not chunk:
            break
        output.extend(chunk)
        if marker in output and b"\n" in output[output.index(marker):]:
            break
        if failure in output and b"\n" in output[output.index(failure):]:
            break
finally:
    selector.close()

if process.poll() is None:
    process.terminate()
    try:
        process.wait(timeout=2)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()
remaining = process.stdout.read() or b""
output.extend(remaining)
text = output.decode(errors="replace")
if marker in output:
    text = output.decode(errors="replace")
    line_end = text.find("\n", text.index(marker.decode()))
    if line_end >= 0:
        text = text[:line_end + 1]
print(text, end="")
if marker in output:
    raise SystemExit(0)
if failure in output:
    raise SystemExit("QEMU event/DMA probe reported FAIL")
raise SystemExit(f"QEMU event/DMA probe timed out or exited before PASS (exit={process.returncode})")
PY
