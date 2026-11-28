#!/usr/bin/env bash
# Verify the browser's filesystem-operation dispatch and its directory modes.
# Static Ghidra/decompiler check only; this does not execute or modify firmware.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:-$ROOT/analysis/community/fw/browser.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}

[ -f "$AP" ] || { echo "missing browser AP: $AP" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-browser-fs.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project" "$WORK/home"

set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30browserfs \
  -import "$AP" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript LoadAP.java "$AP" \
  -postScript DumpWords.java 0x780db220 0x780db280 \
  -postScript DumpWords.java 0x780db5c8 0x780db5e0 \
  -postScript DumpAt.java \
    0x48754574,0x71352820,0x780db200,0x713125dc,0x714525ac,0x713925f4 \
    "$WORK/decomp.c" 2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"

for marker in \
  'DumpAt: 48754574 -> FUN_48754574' \
  'DumpAt: 71352820 -> FUN_71352820' \
  'DumpAt: 780db200 -> FUN_780db200' \
  'DumpAt: 713125dc -> FUN_713125dc' \
  'DumpAt: 714525ac -> FUN_714525ac' \
  'DumpAt: 713925f4 -> FUN_713925f4' \
  'WORD address=780db220 value=0x9fc15735' \
  'WORD address=780db224 value=0x780db5d4' \
  'WORD address=780db228 value=0x9fc15734' \
  'WORD address=780db25c value=0x780db5c8' \
  'WORD address=780db5c8 value=0x71352821' \
  'WORD address=780db5d0 value=0x71392659' \
  'WORD address=780db5d4 value=0x713125dd' \
  'WORD address=780db5d8 value=0x714525ad' \
  'WORD address=780db5dc value=0x713925f5'; do
  grep -q "$marker" "$WORK/ghidra.log"
done

# Stock keeps the original library handler while community/V1.101.11 use the
# replacement handler.  Both variants share the same directory dispatch and
# are useful cross-checks for this boundary.
if grep -q 'WORD address=780db5cc value=0x714d2659' "$WORK/ghidra.log"; then
  FSEL_LIBRARY_HANDLER=community
elif grep -q 'WORD address=780db5cc value=0x714126e1' "$WORK/ghidra.log"; then
  FSEL_LIBRARY_HANDLER=stock
else
  echo 'unexpected library fsel handler' >&2
  exit 1
fi

# The wrapper at 0x780db200 selects a filesystem implementation by the
# resident state byte.  The three browser directory operations are the
# explicit mode values in FUN_713125dc: enter, parent, and root.
grep -Fq '(**(code **)((uint)*pbVar1 * 4 + _UNK_780db224))(param_1,param_2);' "$WORK/decomp.c"
grep -Fq 'if (param_1 == 1)' "$WORK/decomp.c"
grep -Fq 'else if (param_1 == 2)' "$WORK/decomp.c"
grep -Fq 'if (param_1 != 3)' "$WORK/decomp.c"
grep -Fq '0x2f,0,0,0' "$WORK/decomp.c"
grep -Fq '0x5c,0,0,0' "$WORK/decomp.c"
grep -Fq '0x3a,0,0,0' "$WORK/decomp.c"

echo "BROWSER FS DISPATCH PASS wrapper=0x780db201 modes=1/2/3 directory-op=0x713125dd fsel-types=0/1/2 library-handler=$FSEL_LIBRARY_HANDLER"
