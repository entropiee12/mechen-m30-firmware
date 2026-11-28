#!/usr/bin/env bash
# Validate the real LCD helper in QEMU, then render the visible hybrid preview.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:-$ROOT/build/qemu-minimal-safe/work/verify/fw/music.ap}
STY=${2:-$ROOT/build/qemu-minimal-safe/work/verify/fw/music.sty}
OUT=${3:-/private/tmp/m30-lcd-preview.ppm}
TRACE=${4:-/private/tmp/m30-lcd-preview.json}

[ -f "$AP" ] || { echo "missing music.ap: $AP" >&2; exit 1; }
[ -f "$STY" ] || { echo "missing music.sty: $STY" >&2; exit 1; }

bash "$ROOT/tools/emu/run_qemu_lcd_probe.sh"
python3 "$ROOT/tools/emu/lcd_preview.py" \
  --music-ap "$AP" \
  --music-sty "$STY" \
  --out "$OUT" \
  --trace "$TRACE"
echo "LCD PREVIEW READY image=$OUT trace=$TRACE"
