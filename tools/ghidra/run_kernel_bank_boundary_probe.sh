#!/usr/bin/env bash
# Check whether the resident kernel image contains the observed UI far-pointer
# window constants.  This is static analysis only; it does not execute ROM.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
KERNEL=${1:-$ROOT/analysis/community/fw/kernel.drv}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}

[ -f "$KERNEL" ] || { echo "missing kernel: $KERNEL" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-kernel-constants.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project"

set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30kconst \
  -import "$KERNEL" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript FlatLoad.java "$KERNEL" 0xbfc08000 0 \
  -postScript FindPointerRefs.java 0x00009000,0x0003ffff,0x00040000,0x00025000,0x00000400 \
  2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"

# The resident image has the generic 0x3ffff mask and unrelated 0x400/0x40000
# constants, but no literal 0x9000 or 0x25000 UI-window base.
grep -q 'POINTER_TOTAL value=0x00009000 count=0' "$WORK/ghidra.log"
grep -q 'POINTER_TOTAL value=0x0003ffff count=1' "$WORK/ghidra.log"
grep -q 'POINTER_TOTAL value=0x00040000 count=5' "$WORK/ghidra.log"
grep -q 'POINTER_TOTAL value=0x00025000 count=0' "$WORK/ghidra.log"
grep -q 'POINTER_TOTAL value=0x00000400 count=3' "$WORK/ghidra.log"
echo 'KERNEL BANK BOUNDARY PASS no_0x9000=1 no_0x25000=1 mask_0x3ffff=1'
