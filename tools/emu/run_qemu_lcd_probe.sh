#!/usr/bin/env bash
# Execute real drv_lcd.drv window/orientation helpers with RAM MMIO shims.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/drv_lcd.drv}
CROSS=${CROSS:-mipsel-linux-gnu-}
QEMU=${QEMU:-qemu-system-mipsel}
PY=${PYTHON:-python3}
OUT=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-lcd-qemu.XXXXXX")
trap 'rm -rf "$OUT"' EXIT

[ -f "$DRIVER" ] || { echo "missing LCD driver: $DRIVER" >&2; exit 1; }
for tool in "${CROSS}as" "${CROSS}ld" "$QEMU" "$PY"; do
  command -v "$tool" >/dev/null || { echo "missing required tool: $tool" >&2; exit 1; }
done

"$PY" - "$DRIVER" "$OUT/lcd_code.bin" <<'PY'
from pathlib import Path
import struct
import sys

source = Path(sys.argv[1]).read_bytes()
start, size = 0x200, 0x800
segment = bytearray(source[start : start + size])
if len(segment) != size:
    raise SystemExit("drv_lcd.drv resident segment is truncated")

stub = 0x80012001
real_command = 0x80050001
real_data = 0x8005002D
relocations = {
    0x0024: 0x80060008,  # FUN_bfc0a000 control register
    0x0028: 0x8006000C,  # FUN_bfc0a000 command register
    0x0068: 0x80060010,  # FUN_bfc0a02c control register
    0x006C: 0x80060014,  # FUN_bfc0a02c data register
    0x0424: stub,         # window setup callback
    0x0428: 0x8006001C,   # orientation mode
    0x042C: real_command,
    0x0430: real_data,
    0x0434: stub,         # window transfer callback
    0x04CC: stub,         # orientation setup callback
    0x04D0: real_command,
    0x04D4: 0x80060018,   # raw orientation state
    0x04D8: 0x8006001C,   # orientation mode
    0x04DC: real_data,
    0x04E0: stub,          # orientation transfer callback
}
for offset, value in relocations.items():
    struct.pack_into("<I", segment, offset, value)
Path(sys.argv[2]).write_bytes(segment)
PY

(
  cd "$OUT"
  "${CROSS}as" -EL -mabi=32 -mips32 -I "$OUT" \
    -o qemu_lcd_probe.o "$ROOT/tools/emu/qemu_lcd_probe.S"
  "${CROSS}ld" -EL -m elf32ltsmip \
    -T "$ROOT/tools/emu/qemu_lcd_probe.ld" \
    -o qemu_lcd_probe.elf qemu_lcd_probe.o
)

"$PY" - "$QEMU" "$OUT/qemu_lcd_probe.elf" <<'PY'
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
    if "REAL LCD QEMU PASS" in output:
        raise SystemExit(0)
    raise SystemExit("QEMU LCD probe timed out before PASS")

print(result.stdout, end="")
if "REAL LCD QEMU PASS" not in result.stdout:
    raise SystemExit(f"QEMU LCD probe failed (exit={result.returncode})")
PY
