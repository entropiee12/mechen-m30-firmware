#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
XSYS=${1:-$ROOT/analysis/community/fw/ker_xsys.bin}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}
EXPECTED_SHA256=deaf67edce7eca6397abd1ca91f742ccbe70f451bb4926c75c7e46862e3b2988

[ -f "$XSYS" ] || { echo "missing ker_xsys.bin: $XSYS" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
INPUT_SHA=$(shasum -a 256 "$XSYS" | awk '{print $1}')
[ "$INPUT_SHA" = "$EXPECTED_SHA256" ] || {
  echo "ker_xsys.bin SHA-256 $INPUT_SHA != $EXPECTED_SHA256" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-xsys-alt-stack.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project"

JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" xsysaltstack \
  -import "$XSYS" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript MapSegments.java 1 "$XSYS" 0xbfc34000 0 0x800 0 \
  -postScript RunKerXsysAlternateStackProbe.java "$XSYS" \
  >"$WORK/run.log" 2>&1 || {
    cat "$WORK/run.log" >&2
    exit 1
  }

grep -Fq 'KER XSYS ALTERNATE STACK PROFILE PASS label=below sp-mask=0x11fff limit=0x12000 path=alternate' "$WORK/run.log" || {
  cat "$WORK/run.log" >&2
  exit 1
}
grep -Fq 'KER XSYS ALTERNATE STACK PROFILE PASS label=equal sp-mask=0x12000 limit=0x12000 path=alternate' "$WORK/run.log" || {
  cat "$WORK/run.log" >&2
  exit 1
}
grep -Fq 'KER XSYS ALTERNATE STACK PROFILE PASS label=above sp-mask=0x12001 limit=0x12000 path=direct' "$WORK/run.log" || {
  cat "$WORK/run.log" >&2
  exit 1
}
grep 'KER XSYS ALTERNATE STACK' "$WORK/run.log"
AFTER_SHA=$(shasum -a 256 "$XSYS" | awk '{print $1}')
[ "$INPUT_SHA" = "$AFTER_SHA" ] || {
  echo "input SHA-256 changed: before=$INPUT_SHA after=$AFTER_SHA" >&2
  exit 1
}
echo "KER XSYS INPUT SHA256=$AFTER_SHA"
