#!/usr/bin/env bash
# Decompile the two B02 far-entry candidates from the real drv_ui.drv bank.
# This is static Ghidra analysis only; it does not execute or modify firmware.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/drv_ui.drv}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}

if [ -z "${JAVA_HOME:-}" ] && command -v brew >/dev/null 2>&1; then
  JAVA_PREFIX=$(brew --prefix openjdk@25 2>/dev/null || true)
  JAVA_HOME="$JAVA_PREFIX/libexec/openjdk.jdk/Contents/Home"
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  export JAVA_HOME
fi

[ -f "$DRIVER" ] || { echo "missing driver: $DRIVER" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

# B02 starts at file 0x8680 and public-SDK-derived runtime 0x260a5000.  Map
# the 12-byte lead-in as well, matching the B3 runtime probe.  The pointer
# 0x26089471 has its MIPS16 low bit set and therefore enters at even address
# 0x260a5470; 0x260894e9 maps to the data-looking odd address 0x260a54e9.
FILE_OFFSET=0x8674
RUNTIME_BASE=0x2609c980
SIZE=0x80c
WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-b2.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project" "$WORK/home"

set +e
JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Duser.home=$WORK/home" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:-} -Duser.home=$WORK/home" \
  "$GHIDRA" "$WORK/project" m30b2 \
  -import "$DRIVER" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript FlatLoad.java "$DRIVER" "$RUNTIME_BASE" "$FILE_OFFSET" "$SIZE" \
  -postScript DumpAt.java 0x260a5470,0x260a54e9 "$WORK/b2.c" 2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"

grep -q 'DumpAt: 260a5470 -> FUN_260a5470' "$WORK/ghidra.log"
grep -q 'DumpAt: 260a54e9 -> FUN_260a54e9' "$WORK/ghidra.log"
grep -q 'return 2;' "$WORK/b2.c"
grep -q 'halt_baddata' "$WORK/b2.c"
echo 'DRV_UI B2 CANDIDATE PASS valid_even=0x260a5470 invalid=0x260a54e9'
