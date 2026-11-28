#!/usr/bin/env bash
# Reproduce the resident kernel-table helper decompilation.
# This is a static Ghidra probe; it never writes firmware or a flash image.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/kernel.drv}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}

[ -f "$DRIVER" ] || { echo "missing kernel driver: $DRIVER" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-kloader.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project"
FILE_SIZE=$(stat -f '%z' "$DRIVER")
BODY_SIZE=$((FILE_SIZE - 0x200))

set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30kernel \
  -import "$DRIVER" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript FlatLoad.java "$DRIVER" 0xbfc07e00 0x200 "$BODY_SIZE" \
  -postScript MarkFunction.java 0xbfc0a408,0xbfc0a578 \
  -postScript DumpAt.java 0xbfc0a408,0xbfc0a578 "$WORK/decomp.c" 2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"

grep -q 'DumpAt: bfc0a408' "$WORK/ghidra.log"
grep -q 'DumpAt: bfc0a578' "$WORK/ghidra.log"
grep -Fq 'pcVar1 = _UNK_bfc0a480;' "$WORK/decomp.c"
grep -Fq '*param_4 = param_1;' "$WORK/decomp.c"
echo 'KERNEL RESIDENT TABLE PASS a408=helper a578=state-copy yqhx-loader=unresolved'
