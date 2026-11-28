#!/usr/bin/env bash
# Execute the producer and manually compose the consumer in QEMU.
# FIFO is RAM-backed; the consumer call edge is supplied by this harness.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/usound.drv}
CROSS=${CROSS:-mipsel-linux-gnu-}
QEMU=${QEMU:-qemu-system-mipsel}
PY=${PYTHON:-python3}
EXPECTED_SHA256=e593bdd29c2a96d4d745cf1b5cae4da2ee71c2211c551e415209b18f54e600fc
OUT=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-usound-composition.XXXXXX")
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
    0x1024: 0x80060000,
    0x1028: stub,
    0x102C: 0x80060004,
    0x1030: 0x80060008,
    0x1034: 0x80060009,
    0x1038: 0x80068000,  # producer scratch; candidate consumer ring shares address
    0x103C: stub,
    0x1040: 0x8FC318CD,  # real FUN_bfc318cc word-port helper
    0x1044: 0x80062020,
    0x104C: 0x8FC30CE9,
    0x1050: 0x8FC30CAD,
    0x1054: 0x80060018,
    0x1058: 0x80061000,  # separate producer/DMA ring
    0x105C: 0x80060020,
    0x1060: 0x80060024,
    0x1064: 0x80060028,
    0x1068: stub,
    0x1190: 0x80066000,
    0x1194: 0x80066004,
    0x1198: 0x80066008,
    0x119C: stub,
    # Consumer state aliases; shared count/offset are supplied by this harness.
    0x1728: 0x80060020,  # available bytes
    0x172C: 0x8006002C,  # consumer short-read counter
    0x1730: 0x8006001C,  # consumer read offset
    0x1734: 0x80068000,  # consumer ring hypothesis, same address as producer scratch
    0x1738: 0x8FC326A9,  # real module-0x10 break stub
    0x173C: 0x80060024,  # shared ready/reset signal
    # Real word-port helper backed by deterministic guest RAM.
    0x194C: 0x80066708,
    0x1950: 0x8006670C,
    0x1954: 0x80066710,
}
for offset, value in relocations.items():
    struct.pack_into("<I", segment, offset, value)
Path(sys.argv[2]).write_bytes(segment)
PY

(
  cd "$OUT"
  "${CROSS}as" -EL -mabi=32 -mips32 \
    -o qemu_usound_composition_probe.o "$ROOT/tools/emu/qemu_usound_composition_probe.S"
  "${CROSS}ld" -EL -m elf32ltsmip \
    -T "$ROOT/tools/emu/qemu_usound_composition_probe.ld" \
    -o qemu_usound_composition_probe.elf qemu_usound_composition_probe.o
)

"$PY" - "$QEMU" "$OUT/qemu_usound_composition_probe.elf" <<'PY'
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
process = subprocess.Popen(command, stdout=subprocess.PIPE,
                           stderr=subprocess.STDOUT)
output = bytearray()
deadline = time.monotonic() + float(os.environ.get("QEMU_TIMEOUT", "12"))
selector = selectors.DefaultSelector()
selector.register(process.stdout, selectors.EVENT_READ)
marker = b"SYNTHETIC USOUND COMPOSITION MISMATCH"
failure = b"SYNTHETIC USOUND COMPOSITION FAIL"
try:
    while process.poll() is None and time.monotonic() < deadline:
        if not selector.select(max(0.0, deadline - time.monotonic())):
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
output.extend(process.stdout.read() or b"")
text = output.decode(errors="replace")
if failure in output:
    print(text, end="")
    raise SystemExit("QEMU composition probe reported FAIL")
if marker in output:
    marker_start = text.index(marker.decode())
    line_end = text.find("\n", marker_start)
    if line_end >= 0:
        print(text[marker_start:line_end + 1], end="")
    raise SystemExit(0)
print(text, end="")
raise SystemExit(f"QEMU composition probe timed out or exited before observation (exit={process.returncode})")
PY
