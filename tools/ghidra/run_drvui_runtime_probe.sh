#!/usr/bin/env bash
# Remap the real drv_ui B3 bank at its public- SDK-derived runtime address.
# This is a static Ghidra/decompiler probe: it does not modify firmware or
# produce a HEX file.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/drv_ui.drv}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}

[ -f "$DRIVER" ] || { echo "missing driver: $DRIVER" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

# B3 starts at file 0x9680 and maps to 0x260e5000.  The routine entry is the
# 12-byte lead-in at file 0x9674, hence 0x260e4ff4.
FILE_OFFSET=0x9674
RUNTIME_BASE=0x260db980       # 0x260e4ff4 - 0x9674
RUNTIME_ENTRY=0x260e4ff4
SIZE=0x80c                   # through the end of the 0x800-byte B3 window
WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-drvui.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project"

set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30drvui \
  -import "$DRIVER" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript FlatLoad.java "$DRIVER" "$RUNTIME_BASE" "$FILE_OFFSET" "$SIZE" \
  -postScript DumpAt.java "$RUNTIME_ENTRY" "$WORK/decomp.c" \
  -postScript DumpWords.java 0x260e52bc 0x260e52f0 2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"

grep -q 'DumpAt: 260e4ff4 -> FUN_260e4ff4' "$WORK/ghidra.log"
grep -Fq '(*_UNK_260e52bc)(_UNK_260e52b8,0,0x7c,1);' "$WORK/decomp.c"
grep -Fq 'uStack_40 = 0x7c;' "$WORK/decomp.c"
grep -Fq '(*_UNK_260e52e8)(&uStack_48,0,uVar7);' "$WORK/decomp.c"
grep -Fq '(*_UNK_260e52ec)(&uStack_48,&sStack_28,0);' "$WORK/decomp.c"
grep -q 'WORD address=260e52bc value=0xbfc09c01' "$WORK/ghidra.log"
grep -q 'WORD address=260e52e8 value=0xbfc09e35' "$WORK/ghidra.log"
grep -q 'WORD address=260e52ec value=0x26409401' "$WORK/ghidra.log"
echo 'DRV_UI B3 RUNTIME PASS entry=0x260e4ff4 bank=0x260e5000 clear-width=0x7c resident-forwarder=0xbfc09e35 sdimage=0x26409401'
