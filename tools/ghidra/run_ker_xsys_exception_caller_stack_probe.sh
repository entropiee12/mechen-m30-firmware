#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
XSYS=${1:-$ROOT/analysis/community/fw/ker_xsys.bin}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}
EXPECTED_SHA256=deaf67edce7eca6397abd1ca91f742ccbe70f451bb4926c75c7e46862e3b2988

[ -f "$XSYS" ] || { echo "missing ker_xsys: $XSYS" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

INPUT_SHA=$(shasum -a 256 "$XSYS" | awk '{print $1}')
[ "$INPUT_SHA" = "$EXPECTED_SHA256" ] || {
  echo "ker_xsys.bin SHA-256 $INPUT_SHA != $EXPECTED_SHA256" >&2
  exit 1
}
WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-kerxsys-caller.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project"

set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" kerxsys-caller \
  -import "$XSYS" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript MapSegments.java 1 "$XSYS" 0xbfc34000 0 0x800 0 \
  -postScript RunKerXsysExceptionCallerStackProbe.java 2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"
grep -q 'KER XSYS EXCEPTION CALLER STACK P-CODE PASS' "$WORK/ghidra.log"

AFTER_SHA=$(shasum -a 256 "$XSYS" | awk '{print $1}')
[ "$INPUT_SHA" = "$AFTER_SHA" ] || {
  echo "input SHA-256 changed: before=$INPUT_SHA after=$AFTER_SHA" >&2
  exit 1
}
echo "KER XSYS INPUT SHA256=$AFTER_SHA"
