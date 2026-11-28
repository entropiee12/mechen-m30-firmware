#!/usr/bin/env bash
# Dynamically execute the real browser refresh function in Ghidra p-code.
# RAM-backed state and callback stubs are synthetic; no firmware is modified.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:-$ROOT/analysis/community/fw/browser.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}

[ -f "$AP" ] || { echo "missing browser AP: $AP" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-browser-refresh.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project" "$WORK/home"

set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30browserrefresh \
  -import "$AP" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript LoadAP.java "$AP" \
  -postScript RunBrowserRefreshProbe.java 2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"

grep -q 'BROWSER REFRESH CASE label=no-context context=0' "$WORK/ghidra.log"
grep -q 'row-callbacks=0' "$WORK/ghidra.log"
grep -q 'BROWSER REFRESH CASE label=directory-context context=1' "$WORK/ghidra.log"
grep -q 'row-callbacks=7' "$WORK/ghidra.log"
grep -q 'BROWSER REFRESH DYNAMIC PASS context-gated-row-refresh=verified' "$WORK/ghidra.log"

echo 'BROWSER REFRESH PCODE PASS context-gated-row-refresh=verified rows=7'
