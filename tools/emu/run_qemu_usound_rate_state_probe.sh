#!/usr/bin/env bash
# Compare real producer/DMA code from Ghidra-verified SET_CUR state bytes.
# The EP0 request handler is intentionally not invoked.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/usound.drv}
CROSS=${CROSS:-mipsel-linux-gnu-}
QEMU=${QEMU:-qemu-system-mipsel}
PY=${PYTHON:-python3}
OUT=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-usound-rate-state.XXXXXX")
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

# Preserve offsets across the Ghidra-probed RAM mapping:
# 0x9fc33c00+N -> 0x80070000+N. Producer MMIO remains separately mapped:
# C008000B -> 0x80060000 and C0080008/9 -> 0x80060008/09.
for offset in range(0xC38, 0xCA9, 4):
    value, = struct.unpack_from("<I", segment, offset)
    if 0x9FC33C00 <= value <= 0x9FC33E14:
        value = 0x80070000 + value - 0x9FC33C00
    elif 0xC0080180 <= value <= 0xC0080186:
        value = 0x80063180 + value - 0xC0080180
    else:
        raise SystemExit(f"unexpected mapped literal at {offset:#x}: {value:#010x}")
    struct.pack_into("<I", segment, offset, value)

producer_expected = {
    0x1024: 0xC008000B, 0x1028: 0xBFC30D9D, 0x102C: 0x9FC33DD8,
    0x1030: 0xC0080008, 0x1034: 0xC0080009, 0x1038: 0x9FC34000,
    0x103C: 0xBFC326A9, 0x1040: 0xBFC318CD, 0x1044: 0x9FC33E20,
    0x104C: 0xBFC30CE9, 0x1050: 0xBFC30CAD, 0x1054: 0x9FC33DE8,
    0x1058: 0x9FC24000, 0x105C: 0x9FC33DF0, 0x1060: 0x9FC33E13,
    0x1064: 0x9FC33DF8, 0x1068: 0xBFC30D69,
    0x12D0: 0xC00C0004, 0x12D4: 0x9FC33DF0, 0x12D8: 0x9FC33E13,
    0x12DC: 0xC0000110, 0x136C: 0xC0000000, 0x1370: 0xC0001004,
    0x1374: 0xC00C0040, 0x1378: 0xC00C0004, 0x137C: 0xC00C0008,
    0x1380: 0xC00C0044, 0x1384: 0x9FC24000, 0x1388: 0x9FC33E20,
    0x138C: 0xC00C0054, 0x14B8: 0xC0000110, 0x14C0: 0xC000100C,
    0x14C4: 0xC0050024, 0x14C8: 0xC0000000, 0x14CC: 0xC0050000,
    0x14D0: 0xC005001C, 0x14D4: 0xC0050004, 0x14D8: 0xC0050014,
    0x14DC: 0xC0050018,
}
for offset, expected in producer_expected.items():
    actual, = struct.unpack_from("<I", segment, offset)
    if actual != expected:
        raise SystemExit(
            f"unexpected producer literal at {offset:#x}: "
            f"{actual:#010x} != {expected:#010x}"
        )

callback = 0x80012001
producer_literals = {
    0x1024: 0x80060000,  # C008000B: producer status MMIO byte
    0x1028: callback,
    0x102C: 0x800701D8,  # 0x9fc33dd8: stop flag, offset +0x1d8
    0x1030: 0x80060008,  # USB length low byte
    0x1034: 0x80060009,  # USB length high byte
    0x1038: 0x80072000,  # packet staging buffer
    0x103C: callback,
    0x1040: callback,
    0x1044: 0x80070220,  # 0x9fc33e20: format flag, offset +0x220
    0x104C: 0x8FC30CE9,
    0x1050: 0x8FC30CAD,
    0x1054: 0x800701E8,  # write offset in remapped driver data region
    0x1058: 0x80080000,  # software ring
    0x105C: 0x800701F0,  # queued-byte count
    0x1060: 0x80070213,  # ready latch
    0x1064: 0x800701F8,  # 0x9fc33df8: reset flag, offset +0x1f8
    0x1068: callback,
    0x136C: 0x80064000,
    0x1370: 0x80065004,
    0x1374: 0x80064040,
    0x1378: 0x80064004,
    0x137C: 0x80064008,
    0x1380: 0x80064044,
    0x1384: 0x80080000,
    0x1388: 0x80070220,
    0x138C: 0x80064054,
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
    0x12D4: 0x800701F0,
    0x12D8: 0x80070213,
    0x12DC: 0x80064010,
}
for offset, value in producer_literals.items():
    struct.pack_into("<I", segment, offset, value)

Path(sys.argv[2]).write_bytes(segment)
PY

run_case() {
  local rate=$1
  local name=$2
  (
    cd "$OUT"
    "${CROSS}as" -EL -mabi=32 -mips32 --defsym RATE_HZ="$rate" -I "$OUT" \
      -o "${name}.o" "$ROOT/tools/emu/qemu_usound_rate_state_probe.S"
    "${CROSS}ld" -EL -m elf32ltsmip \
      -T "$ROOT/tools/emu/qemu_usound_ep0_stream_probe.ld" \
      -o "${name}.elf" "${name}.o"
  )
  "$PY" - "$QEMU" "$OUT/${name}.elf" "$rate" <<'PY'
import subprocess
import sys

qemu, elf, rate = sys.argv[1:]
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
    if "RATE_STATE PASS" not in output:
        print(output, end="")
        raise SystemExit(f"QEMU rate-state probe timed out before PASS ({rate} Hz)")
else:
    output = result.stdout
    if "RATE_STATE PASS" not in output:
        print(output, end="")
        raise SystemExit(f"QEMU rate-state probe failed ({rate} Hz, exit={result.returncode})")

lines = [line.strip() for line in output.splitlines() if "RATE_STATE PASS" in line]
if len(lines) != 1:
    raise SystemExit(f"expected one guest result line for {rate} Hz; found {len(lines)}")
print(lines[0])
PY
}

echo "HARNESS EP0_HANDLER=NOT_INVOKED"
echo "MAP firmware_ram=0x9fc33c00+N->guest_ram=0x80070000+N producer_status_mmio=0xc008000b->0x80060000 usb_length_mmio=c0080008/9->0x80060008/09"
echo "PACKET format=direct-16-bit length=192 pattern=0..191 repetitions=74"
run_case 48000 rate_48000 | tee "$OUT/48000.out"
run_case 32000 rate_32000 | tee "$OUT/32000.out"
"$PY" - "$OUT/48000.out" "$OUT/32000.out" <<'PY'
from pathlib import Path
import re
import sys

lines = [Path(path).read_text().strip() for path in sys.argv[1:]]
expected = [
    ("rate_hz=0x0000BB80", "rate_bytes_le=0x80BB00"),
    ("rate_hz=0x00007D00", "rate_bytes_le=0x007D00"),
]
if len(lines) != len(expected):
    raise SystemExit("expected two rate-state guest results")
normalized = []
for line, (expected_rate, expected_bytes) in zip(lines, expected):
    rate_match = re.search(r"(?:^|\s)rate_hz=(0x[0-9a-fA-F]+)(?:\s|$)", line)
    bytes_match = re.search(r"(?:^|\s)rate_bytes_le=(0x[0-9a-fA-F]+)(?:\s|$)", line)
    if (not rate_match or not bytes_match
            or rate_match.group(1).lower() != expected_rate.split("=", 1)[1].lower()
            or bytes_match.group(1).lower() != expected_bytes.split("=", 1)[1].lower()):
        raise SystemExit(
            f"seeded rate mismatch: expected {expected_rate} / {expected_bytes}; got {line}"
        )
    line = re.sub(r"rate_hz=0x[0-9a-fA-F]+", "rate_hz=<rate>", line)
    line = re.sub(r"rate_bytes_le=0x[0-9a-fA-F]+", "rate_bytes_le=<bytes>", line)
    normalized.append(line)
if normalized[0] != normalized[1]:
    raise SystemExit("producer/DMA observations differ between sample-rate states")
print("RATE_STATE COMPARISON PASS downstream_observations_identical=1 EP0_handler=NOT_INVOKED")
PY
