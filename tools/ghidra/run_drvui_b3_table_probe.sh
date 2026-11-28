#!/usr/bin/env bash
# Inspect the pointer table immediately following the real M30 B3 bank.
# Static analysis only; this does not execute or modify firmware.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/drv_ui.drv}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}

[ -f "$DRIVER" ] || { echo "missing driver: $DRIVER" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-b3-table.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project"

set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30b3table \
  -import "$DRIVER" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript MapSegments.java 2 \
    "$DRIVER" 0x260db980 0x9674 0x80c 1 \
    "$DRIVER" 0x260db980 0x9e80 0x220 0 \
  -postScript DumpAt.java 0x260e5780 \
  -postScript DumpWords.java 0x260e59e8 0x260e5a14 \
  2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"

grep -q 'MapSegments.java> MapSegments: .* -> 0x260e5800 size=0x220 isa=0' "$WORK/ghidra.log"
grep -q 'WORD address=260e59e8 value=0xbfc09c05' "$WORK/ghidra.log"
grep -q 'WORD address=260e59ec value=0xbfc09c01' "$WORK/ghidra.log"
grep -q 'WORD address=260e59f0 value=0x26209401' "$WORK/ghidra.log"
grep -q 'WORD address=260e59f4 value=0x261c9694' "$WORK/ghidra.log"
grep -q 'WORD address=260e5a10 value=0x26109751' "$WORK/ghidra.log"

echo 'DRV_UI B3 TABLE PASS resident=2 bank_qualified=3'
