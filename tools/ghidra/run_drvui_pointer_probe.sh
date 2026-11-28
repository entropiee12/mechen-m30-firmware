#!/usr/bin/env bash
# Report observed far-pointer literals with their containing Ghidra functions.
# This is static analysis only; it does not execute or modify firmware.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/drv_ui.drv}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}
POINTERS=${POINTERS:-0x164c9001,0x164c90ed,0x26089471,0x260894e9,0x26409401}

[ -f "$DRIVER" ] || { echo "missing driver: $DRIVER" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-pointers.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project"

# Omit FlatLoad's optional size so it maps the complete driver.  The literals
# are in the B/C bank area, not in the header's first 0x200 bytes.

set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30pointers \
  -import "$DRIVER" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript FlatLoad.java "$DRIVER" 0x30000000 0 \
  -postScript FindPointerRefs.java "$POINTERS" 2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"

grep -q 'POINTER_TOTAL value=0x164c9001 count=4' "$WORK/ghidra.log"
grep -q 'POINTER_TOTAL value=0x164c90ed count=5' "$WORK/ghidra.log"
grep -q 'POINTER_TOTAL value=0x26089471 count=3' "$WORK/ghidra.log"
grep -q 'POINTER_TOTAL value=0x260894e9 count=2' "$WORK/ghidra.log"
grep -q 'POINTER_TOTAL value=0x26409401 count=2' "$WORK/ghidra.log"
echo "DRV_UI POINTER PASS pointers=$POINTERS"
