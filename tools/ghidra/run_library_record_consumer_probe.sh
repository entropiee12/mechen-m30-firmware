#!/usr/bin/env bash
# Trace MUSIC.LIB record-location consumers in music.ap.
# Static Ghidra analysis only; no firmware or library is modified.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
MUSIC=${1:-$ROOT/analysis/community/fw/music.ap}
OUTPUT_DIR=${2:-$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-record-consumers.XXXXXX")}
GHIDRA=${GHIDRA:-/opt/homebrew/opt/ghidra/libexec/support/analyzeHeadless}

if [ -z "${JAVA_HOME:-}" ] && command -v brew >/dev/null 2>&1; then
  JAVA_PREFIX=$(brew --prefix openjdk@25 2>/dev/null || true)
  JAVA_HOME="$JAVA_PREFIX/libexec/openjdk.jdk/Contents/Home"
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  export JAVA_HOME
fi

[ -f "$MUSIC" ] || { echo "missing music.ap: $MUSIC" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

mkdir -p "$OUTPUT_DIR/home" "$OUTPUT_DIR/project"

JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:--Duser.home=$OUTPUT_DIR/home}" \
  "$GHIDRA" "$OUTPUT_DIR/project" m30recordconsumers \
  -import "$MUSIC" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript LoadAP.java "$MUSIC" \
  -postScript DumpAt.java \
    0x71092500,0x712d27f4,0x712d2a9c,0x71352810,0x713528ec,0x717d282c,0x7181291c \
    "$OUTPUT_DIR/consumers.c" containing \
    >"$OUTPUT_DIR/run.log" 2>&1

for address in \
  '71092500 -> FUN_71092500' \
  '712d27f4 -> FUN_712d27f4' \
  '712d2a9c -> FUN_712d2a9c' \
  '71352810 -> FUN_71352810' \
  '713528ec -> FUN_713528ec' \
  '717d282c -> FUN_717d282c' \
  '7181291c -> FUN_7181291c'; do
  grep -q "DumpAt: $address" "$OUTPUT_DIR/run.log"
done

# Known readers compare/copy the eight-byte secondary locator and the primary
# location blob. No direct record predecessor/successor access was present in
# these consumers; keep that negative result explicit rather than decoding it.
grep -q '0xf4' "$OUTPUT_DIR/consumers.c"
grep -q '0xa9' "$OUTPUT_DIR/consumers.c"
grep -q '0xa4' "$OUTPUT_DIR/consumers.c"
! grep -Eq '\+ 0xfc|\+ 0xfe' "$OUTPUT_DIR/consumers.c"

echo "LIBRARY RECORD CONSUMER GHIDRA PASS output=$OUTPUT_DIR"
