#!/usr/bin/env bash
# Run the recovered USB Audio boundary as freestanding guest MIPS code.
# This is a QEMU CPU/RAM smoke test, not an ATJ2127 or M30 firmware boot.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
CROSS=${CROSS:-mipsel-linux-gnu-}
QEMU=${QEMU:-qemu-system-mipsel}
PY=${PYTHON:-python3}
OUT=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-usb-qemu.XXXXXX")
trap 'rm -rf "$OUT"' EXIT

for tool in "${CROSS}as" "${CROSS}ld" "$QEMU" "$PY"; do
  command -v "$tool" >/dev/null || { echo "missing required tool: $tool" >&2; exit 1; }
done

"${CROSS}as" -EL -mabi=32 -mips32 \
  -o "$OUT/qemu_usb_audio_probe.o" \
  "$ROOT/tools/emu/qemu_usb_audio_probe.S"
"${CROSS}ld" -EL -m elf32ltsmip \
  -T "$ROOT/tools/emu/qemu_usb_audio_probe.ld" \
  -o "$OUT/qemu_usb_audio_probe.elf" \
  "$OUT/qemu_usb_audio_probe.o"

"$PY" - "$QEMU" "$OUT/qemu_usb_audio_probe.elf" <<'PY'
import subprocess
import sys

qemu, elf = sys.argv[1:]
command = [
    qemu,
    "-M", "malta",
    "-cpu", "24Kc",
    "-m", "16M",
    "-nographic",
    "-monitor", "none",
    # Malta's FPGA UART is serial backend 2 (the first two are PCI UARTs).
    "-serial", "null",
    "-serial", "null",
    "-serial", "stdio",
    "-kernel", elf,
]
try:
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, timeout=8)
except subprocess.TimeoutExpired as exc:
    output = exc.stdout or ""
    if isinstance(output, bytes):
        output = output.decode(errors="replace")
    print(output, end="")
    if "USB AUDIO QEMU BOUNDARY PASS" in output:
        raise SystemExit(0)
    if "USB AUDIO QEMU BOUNDARY FAIL" in output:
        raise SystemExit("QEMU USB Audio probe reported FAIL")
    raise SystemExit("QEMU probe timed out before a PASS/FAIL line")

print(result.stdout, end="")
if "USB AUDIO QEMU BOUNDARY PASS" not in result.stdout:
    raise SystemExit(f"QEMU USB Audio probe failed (exit={result.returncode})")
PY
