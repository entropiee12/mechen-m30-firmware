#!/usr/bin/env bash
# Check whether the EQ selector field is written by a direct immediate-offset store.
# Static/decompiler-only; this never patches firmware or creates a HEX image.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-eq-writer.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

for app in setting music mengine; do
  mkdir -p "$WORK/$app/home" "$WORK/$app/project"
  GHIDRA_JAVA_OPTIONS="-Duser.home=$WORK/$app/home" \
    "$GHIDRA" "$WORK/$app/project" "${app}eqwriter" \
    -import "$ROOT/analysis/community/fw/$app.ap" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$ROOT/analysis/community/fw/$app.ap" \
    -postScript FindOffsetStores.java 0x41,0x141 \
    >"$WORK/$app/run.log" 2>&1

  grep -q 'OFFSET_STORE_TOTAL offset=0x41 count=0' "$WORK/$app/run.log"
  grep -q 'OFFSET_STORE_TOTAL offset=0x141 count=0' "$WORK/$app/run.log"
done

echo 'EQ WRITER PROBE PASS direct_immediate_store=none apps=setting,music,mengine'
