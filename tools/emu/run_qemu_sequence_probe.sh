#!/usr/bin/env bash
# Run four real MIPS16 event transitions in one process and verify persistence.
set -euo pipefail

[ $# -ge 1 ] || { echo "usage: $0 MUSIC.AP [OUTDIR]" >&2; exit 2; }
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=$(realpath "$1")
OUT=${2:-/private/tmp/m30-qemu-sequence}
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
PY=${PYTHON:-python3}
CROSS=${CROSS:-mipsel-linux-gnu-}
QEMU=${QEMU:-qemu-mipsel-static}

command -v "$PY" >/dev/null || { echo "missing Python: $PY" >&2; exit 1; }
for tool in "${CROSS}as" "${CROSS}ld"; do
  command -v "$tool" >/dev/null || { echo "missing MIPS binutils: $tool" >&2; exit 1; }
done

PYTHONPATH="$ROOT/tools/emu" "$PY" "$ROOT/tools/emu/make_qemu_probe.py" "$AP" "$OUT"
(
  cd "$OUT"
  "${CROSS}as" -EL -mabi=32 -mips32 --defsym SEQUENCE_MODE=1 -o qemu_probe.o qemu_probe.S
  "${CROSS}as" -EL -mabi=32 -mips32 --defsym PROBE_MODE=1 --defsym SEQUENCE_MODE=1 -o qemu_probe_start.o qemu_probe_start.S
  "${CROSS}as" -EL -mabi=32 -mips16 -o banks.o banks.s
  "${CROSS}ld" -EL -m elf32ltsmip -T qemu_probe.ld -o qemu_sequence_probe.elf qemu_probe_start.o qemu_probe.o banks.o
)
echo "probe ELF: $OUT/qemu_sequence_probe.elf"
if ! command -v "$QEMU" >/dev/null; then
  echo "QEMU user-mode not found: $QEMU; ELF was built but not executed" >&2
  exit 3
fi

STATUS="$OUT/qemu-sequence-status.bin"
"$PY" "$ROOT/tools/emu/run_qemu_bounded.py" \
  --timeout "${QEMU_TIMEOUT:-8}" \
  --stdout "$STATUS" -- "$QEMU" -cpu 24Kc "$OUT/qemu_sequence_probe.elf"
ACTUAL=$(
  "$PY" -c 'import struct, sys; data = open(sys.argv[1], "rb").read();
if len(data) != 20: raise SystemExit(f"expected 20 result bytes, got {len(data)}")
v0, active, previous, status, y = struct.unpack("<IIIII", data)
print(f"0x{v0:04x} {active} {previous} {status} {y}")' "$STATUS"
)
set -- $ACTUAL
if [ "$1 $2 $3 $4 $5" != "0x0000 1 2 1 50" ]; then
  echo "QEMU sequence mismatch: got v0=$1 active=$2 previous=$3 status=$4 y=$5" >&2
  exit 1
fi
printf 'QEMU SCROLL SEQUENCE PASS events=next,next,previous,redraw active=%s previous=%s status=%s y=%s\n' "$2" "$3" "$4" "$5"
