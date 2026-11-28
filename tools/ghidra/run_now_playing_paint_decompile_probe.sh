#!/usr/bin/env bash
# Verify the decompiler-visible rectangle writes in FUN_40154ddc.
# This is static analysis: it never executes or modifies the AP.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:-$ROOT/analysis/community/fw/music.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}

[ -f "$AP" ] || { echo "missing music AP: $AP" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-now-playing-decompile.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30nowplayingdecompile \
  -import "$AP" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript LoadAP.java "$AP" \
  -postScript DumpAt.java 0x40154ddc "$WORK/paint.c" containing \
  2>&1 | tee "$WORK/ghidra.log"
PROBE_EXIT=${PIPESTATUS[0]}
set -e

[ "$PROBE_EXIT" -eq 0 ] || exit "$PROBE_EXIT"
if grep -q 'ERROR REPORT SCRIPT ERROR' "$WORK/ghidra.log"; then
  echo "Ghidra script reported an error; decompile probe is not considered a pass" >&2
  exit 1
fi

grep -q 'DumpAt: 40154ddc -> FUN_40154ddc @ 40154ddc' "$WORK/ghidra.log"
grep -Fq 'uStack_18 = 0;' "$WORK/paint.c"
grep -Fq 'uStack_16 = 0x1c;' "$WORK/paint.c"
grep -Fq 'uStack_16 = 0xb3;' "$WORK/paint.c"
grep -Fq 'uStack_14 = 0x140;' "$WORK/paint.c"
grep -Fq 'uStack_12 = 0x1c;' "$WORK/paint.c"
grep -Fq 'uStack_12 = 0x97;' "$WORK/paint.c"
grep -Fq 'uStack_12 = 0x3d;' "$WORK/paint.c"
grep -Fq '(*_UNK_40155324)(&uStack_18,0,0,0x12);' "$WORK/paint.c"

echo "NOW PLAYING PAINT DECOMPILE PASS entry=0x40154ddc rectangle-header=4-halfwords"
