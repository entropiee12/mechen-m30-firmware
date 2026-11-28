#!/usr/bin/env bash
# Build and, when available, run the isolated real-bank MIPS16 probe.
set -euo pipefail

[ $# -ge 1 ] || { echo "usage: $0 MUSIC.AP [OUTDIR] [cancel|next|previous|redraw]" >&2; exit 2; }
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=$(realpath "$1")
OUT=${2:-/private/tmp/m30-qemu-probe}
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
PY=${PYTHON:-python3}
CROSS=${CROSS:-mipsel-linux-gnu-}
QEMU=${QEMU:-qemu-mipsel-static}
MODE=${3:-cancel}

case "$MODE" in
  cancel) PROBE_MODE=0; EVENT_CODE=0x2906; EXPECTED_V0=0x8014 ;;
  next) PROBE_MODE=1; EVENT_CODE=0x2901; EXPECTED_V0=0x0000 ;;
  previous) PROBE_MODE=1; EVENT_CODE=0x2902; EXPECTED_V0=0x0000 ;;
  redraw) PROBE_MODE=1; EVENT_CODE=0x2903; EXPECTED_V0=0x0000 ;;
  *) echo "unknown probe mode: $MODE" >&2; exit 2 ;;
esac

command -v "$PY" >/dev/null || { echo "missing Python: $PY" >&2; exit 1; }
for tool in "${CROSS}as" "${CROSS}ld"; do
  command -v "$tool" >/dev/null || { echo "missing MIPS binutils: $tool" >&2; exit 1; }
done

PYTHONPATH="$ROOT/tools/emu" "$PY" "$ROOT/tools/emu/make_qemu_probe.py" "$AP" "$OUT"
(
  cd "$OUT"
  "${CROSS}as" -EL -mabi=32 -mips32 --defsym EVENT_CODE="$EVENT_CODE" -o qemu_probe.o qemu_probe.S
  "${CROSS}as" -EL -mabi=32 -mips32 --defsym PROBE_MODE="$PROBE_MODE" -o qemu_probe_start.o qemu_probe_start.S
  "${CROSS}as" -EL -mabi=32 -mips16 -o banks.o banks.s
  "${CROSS}ld" -EL -m elf32ltsmip -T qemu_probe.ld -o qemu_probe.elf qemu_probe_start.o qemu_probe.o banks.o
)
echo "probe ELF: $OUT/qemu_probe.elf"
if command -v "$QEMU" >/dev/null; then
  STATUS="$OUT/qemu-status.bin"
  "$PY" - "$QEMU" "$OUT/qemu_probe.elf" "$STATUS" "${QEMU_TIMEOUT:-8}" <<'PY'
import subprocess
import sys

qemu, elf, status_path, timeout = sys.argv[1:]
try:
    with open(status_path, "wb") as status:
        result = subprocess.run([qemu, "-cpu", "24Kc", elf], stdout=status,
                                timeout=float(timeout), check=False)
except subprocess.TimeoutExpired:
    raise SystemExit(f"QEMU probe timed out after {timeout}s; guest process was terminated")
if result.returncode != 0:
    raise SystemExit(f"QEMU probe exited with status {result.returncode}")
PY
  ACTUAL=$(
    "$PY" -c 'import struct, sys; data = open(sys.argv[1], "rb").read();
if len(data) != 20: raise SystemExit(f"expected 20 result bytes, got {len(data)}")
v0, active, previous, status, y = struct.unpack("<IIIII", data)
print(f"0x{v0:04x} {active} {previous} {status} {y}")' "$STATUS"
  )
  set -- $ACTUAL
  if [ "$1" != "$EXPECTED_V0" ]; then
    echo "QEMU $MODE probe mismatch: expected v0=$EXPECTED_V0, got $1" >&2
    exit 1
  fi
  if [ "$MODE" = next ] && [ "$2 $3 $4 $5" != "1 0 2 40" ]; then
    echo "QEMU next state mismatch: got active=$2 previous=$3 status=$4 y=$5" >&2; exit 1
  fi
  if [ "$MODE" = previous ] && [ "$2 $3 $4 $5" != "2 0 2 40" ]; then
    echo "QEMU previous state mismatch: got active=$2 previous=$3 status=$4 y=$5" >&2; exit 1
  fi
  if [ "$MODE" = redraw ] && [ "$2 $3 $4 $5" != "0 0 1 50" ]; then
    echo "QEMU redraw state mismatch: got active=$2 previous=$3 status=$4 y=$5" >&2; exit 1
  fi
  printf 'QEMU SCROLL PROBE PASS mode=%s v0=%s active=%s previous=%s status=%s y=%s\n' "$MODE" "$1" "$2" "$3" "$4" "$5"
else
  echo "QEMU user-mode not found: $QEMU; ELF was built but not executed" >&2
  exit 3
fi
