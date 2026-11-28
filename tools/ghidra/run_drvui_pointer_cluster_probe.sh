#!/usr/bin/env bash
# Reproduce the B3 literal-pool clustering for observed far pointers.
# Static analysis only; this does not execute or modify firmware.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/drv_ui.drv}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}
POINTERS=0x164c9001,0x164c90ed,0x26089471,0x260894e9,0x26409401

[ -f "$DRIVER" ] || { echo "missing driver: $DRIVER" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-pointer-clusters.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project"

set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30pclusters \
  -import "$DRIVER" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript FlatLoad.java "$DRIVER" 0x30000000 0 \
  -postScript FindPointerClusters.java "$POINTERS" 0x40 \
  2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"

grep -q 'POINTER_CLUSTER_TOTAL hits=16' "$WORK/ghidra.log"
grep -Eq 'POINTER_CLUSTER id=[0-9]+ start=0x992c end=0x996c hits=5' "$WORK/ghidra.log"
echo 'DRV_UI POINTER CLUSTER PASS hits=16 b3_pool=0x992c..0x996c'
