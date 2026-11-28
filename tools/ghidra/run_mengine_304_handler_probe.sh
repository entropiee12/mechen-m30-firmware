#!/usr/bin/env bash
# Execute the real mengine.ap 0x304 handler and its in-binary queue helpers.
# Resident function-pointer boundaries (including mmm_mp and message ack) are stubbed.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
MENGINE=${1:-$ROOT/analysis/community/fw/mengine.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
EXPECTED_SHA256=b569605e9f66ef94023cf82d665ae119f6bbb7ed0c9d8f5e6c5be867ce91be49

[ -f "$MENGINE" ] || { echo "missing mengine.ap: $MENGINE" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
ACTUAL_SHA256=$(shasum -a 256 "$MENGINE" | awk '{print $1}')
[ "$ACTUAL_SHA256" = "$EXPECTED_SHA256" ] || {
  echo "unexpected mengine.ap SHA-256: $ACTUAL_SHA256" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-304-handler.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project"

GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30handler304 \
  -import "$MENGINE" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript LoadAP.java "$MENGINE" \
  -postScript RunMengine304HandlerProbe.java \
  >"$WORK/ghidra.log" 2>&1 || { cat "$WORK/ghidra.log"; exit 1; }

grep -q 'MENGINE 0x304 HANDLER DYNAMIC PASS entry=60110ddc statuses=0,1,2' "$WORK/ghidra.log" || {
  cat "$WORK/ghidra.log"
  exit 1
}
grep 'MENGINE 0x304 LITERALS' "$WORK/ghidra.log"
grep 'MENGINE 0x304 HELPER-LITERALS' "$WORK/ghidra.log"
grep 'MENGINE 0x304 NESTED-LITERALS' "$WORK/ghidra.log"
grep 'MENGINE 0x304 HANDLER TRACE' "$WORK/ghidra.log"
grep 'MENGINE 0x304 HANDLER DYNAMIC PASS' "$WORK/ghidra.log"
