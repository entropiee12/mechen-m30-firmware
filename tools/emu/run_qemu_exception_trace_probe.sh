#!/usr/bin/env bash
# Run the callback probe with an independent passive QEMU TCG exception observer.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/usound.drv}
CC=${CC:-cc}
QEMU_PLUGIN_INCLUDE=${QEMU_PLUGIN_INCLUDE:-/opt/homebrew/include}
GLIB_PREFIX=${GLIB_PREFIX:-$(brew --prefix glib 2>/dev/null || true)}
OUT=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-qemu-plugin.XXXXXX")
trap 'rm -rf "$OUT"' EXIT

command -v "$CC" >/dev/null || { echo "missing C compiler: $CC" >&2; exit 1; }
[ -f "$QEMU_PLUGIN_INCLUDE/qemu-plugin.h" ] || {
  echo "missing qemu-plugin.h under $QEMU_PLUGIN_INCLUDE" >&2
  exit 1
}
[ -n "$GLIB_PREFIX" ] || { echo "missing Homebrew glib prefix" >&2; exit 1; }
[ -f "$GLIB_PREFIX/include/glib-2.0/glib.h" ] || {
  echo "missing glib.h under $GLIB_PREFIX" >&2
  exit 1
}

CC_FLAGS=(
  -I"$QEMU_PLUGIN_INCLUDE"
  -I"$GLIB_PREFIX/include/glib-2.0"
  -I"$GLIB_PREFIX/lib/glib-2.0/include"
)
if [ "$(uname -s)" = Darwin ]; then
  "$CC" -dynamiclib -undefined dynamic_lookup "${CC_FLAGS[@]}" \
    -o "$OUT/libm30_exception_trace.so" \
    "$ROOT/tools/emu/qemu_exception_trace.c"
else
  "$CC" -shared -fPIC "${CC_FLAGS[@]}" \
    -o "$OUT/libm30_exception_trace.so" \
    "$ROOT/tools/emu/qemu_exception_trace.c"
fi

set +e
OUTPUT=$(QEMU_PLUGIN="$OUT/libm30_exception_trace.so" \
  bash "$ROOT/tools/emu/run_qemu_usound_callback_probe.sh" "$DRIVER" 2>&1)
STATUS=$?
set -e
printf '%s\n' "$OUTPUT"
[ "$STATUS" -eq 0 ] || exit "$STATUS"
grep -q 'TCG EXCEPTION' <<<"$OUTPUT"
grep -q 'REAL USOUND MODULE-CALL PASS' <<<"$OUTPUT"
echo 'QEMU TCG EXCEPTION TRACE PASS'
