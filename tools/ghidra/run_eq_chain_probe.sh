#!/usr/bin/env bash
# Verify the M30 EQ message dispatcher -> handler -> coefficient builder chain.
# Static/decompiler-only; this never patches firmware or creates a HEX image.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
MENGINE=${1:-$ROOT/analysis/community/fw/mengine.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}

if [ -z "${JAVA_HOME:-}" ] && command -v brew >/dev/null 2>&1; then
  JAVA_PREFIX=$(brew --prefix openjdk@25 2>/dev/null || true)
  JAVA_HOME="$JAVA_PREFIX/libexec/openjdk.jdk/Contents/Home"
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  export JAVA_HOME
fi

[ -f "$MENGINE" ] || { echo "missing mengine.ap: $MENGINE" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-eq-chain.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30eqchain \
  -import "$MENGINE" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript LoadAP.java "$MENGINE" \
  -postScript DumpWords.java 0x60091010 0x6009101c \
  -postScript DumpWords.java 0x601d0eec 0x601d0ef0 \
  -postScript DumpWords.java 0x601d0f6c 0x601d0f78 \
  -postScript DumpAt.java 0x60090d64,0x601d0f30,0x601d0d58 "$WORK/decomp.c" containing \
  >"$WORK/run.log" 2>&1

for marker in \
  'WORD address=60091014 value=0x601d0f31' \
  'WORD address=601d0eec value=0x9fc15c88' \
  'WORD address=601d0f6c value=0x9fc15ec8' \
  'WORD address=601d0f74 value=0x601d0d59' \
  'DumpAt: 60090d64 -> FUN_60090d64' \
  'DumpAt: 601d0f30 -> FUN_601d0f30' \
  'DumpAt: 601d0d58 -> FUN_601d0d58'; do
  grep -q "$marker" "$WORK/run.log"
done

grep -q 'uVar1 == 0x303' "$WORK/decomp.c"
grep -q '(\*_UNK_60091014)(param_1)' "$WORK/decomp.c"
grep -q '(\*_UNK_601d0f74)(1)' "$WORK/decomp.c"
grep -q 'bVar1 = \*(byte \*)(_UNK_601d0eec + 0x41)' "$WORK/decomp.c"
grep -q 'bVar1 == 7' "$WORK/decomp.c"
grep -q 'bVar1 \* 7' "$WORK/decomp.c"

echo 'EQ CHAIN PROBE PASS msg=0x303 handler=0x601d0f30 builder=0x601d0d58 selector=0x9fc15c88+0x41'
