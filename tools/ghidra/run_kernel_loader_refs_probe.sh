#!/usr/bin/env bash
# Report static references to resident-table candidates in kernel.drv.
# This does not execute the driver or infer calls from raw byte coincidences.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
KERNEL=${1:-$ROOT/analysis/community/fw/kernel.drv}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}

[ -f "$KERNEL" ] || { echo "missing kernel: $KERNEL" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-kernel-refs.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project"

set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30krefs \
  -import "$KERNEL" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript FlatLoad.java "$KERNEL" 0xbfc08000 0 \
  -postScript FindFunctionRefs.java \
    0xbfc0a408,0xbfc0a578,0xbfc0a6a0,0xbfc0a808,0xbfc0a944,0xbfc0ac98 \
  2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"

for target in 0xbfc0a408 0xbfc0a578 0xbfc0a6a0 0xbfc0a808 0xbfc0a944 0xbfc0ac98; do
  grep -q "FUNCTION_TOTAL target=$target count=0" "$WORK/ghidra.log"
done
echo 'KERNEL RESIDENT TABLE REFS PASS targets=6 direct_refs=0'
