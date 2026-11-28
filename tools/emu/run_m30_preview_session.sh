#!/usr/bin/env bash
# Run real LCD/usound QEMU probes, then emit one visible hybrid session.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:-$ROOT/build/qemu-minimal-safe/work/verify/fw/music.ap}
STY=${2:-$ROOT/build/qemu-minimal-safe/work/verify/fw/music.sty}
OUT=${3:-/private/tmp/m30-preview-session.ppm}
TRACE=${4:-/private/tmp/m30-preview-session.json}

[ -f "$AP" ] || { echo "missing music.ap: $AP" >&2; exit 1; }
[ -f "$STY" ] || { echo "missing music.sty: $STY" >&2; exit 1; }

bash "$ROOT/tools/emu/run_qemu_lcd_probe.sh"
bash "$ROOT/tools/emu/run_qemu_usound_callback_probe.sh"
python3 "$ROOT/tools/emu/m30_preview_session.py" \
  --music-ap "$AP" \
  --music-sty "$STY" \
  --out "$OUT" \
  --trace "$TRACE" \
  --callback-module-id 0x10 \
  --callback-args 0 0 0 0 \
  --submit-packet
echo "M30 PREVIEW SESSION READY image=$OUT trace=$TRACE"
