#!/usr/bin/env bash
# Execute the real MMM_BP terminal status-3 branch with one output boundary stubbed.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AL=${1:?usage: run_mmm_bp_status3_probe.sh HASH_MATCHING_MMM_BP.AL}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
EXPECTED_SHA256=ab5d98b578406c4e04fb7b546317fbec0d36ce4a7c139ef0922b06bb4b753c5b

if [ -z "${JAVA_HOME:-}" ] && command -v brew >/dev/null 2>&1; then
  JAVA_PREFIX=$(brew --prefix openjdk@25 2>/dev/null || true)
  JAVA_HOME="$JAVA_PREFIX/libexec/openjdk.jdk/Contents/Home"
fi
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  export JAVA_HOME
fi

[ -f "$AL" ] || { echo "missing mmm_bp.al: $AL" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
actual_sha256=$(shasum -a 256 "$AL" | awk '{print $1}')
[ "$actual_sha256" = "$EXPECTED_SHA256" ] || {
  echo "unexpected mmm_bp.al SHA-256: $actual_sha256" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-mmm-status3.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project" "$WORK/home"

set +e
JAVA_TOOL_OPTIONS="-Duser.home=$WORK/home" \
  "$GHIDRA" "$WORK/project" m30mmmbpstatus3 \
    -import "$AL" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$AL" \
    -postScript RunMmmBpStatus3Probe.java \
    >"$WORK/ghidra.log" 2>&1
status=$?
set -e
[ "$status" -eq 0 ] || { cat "$WORK/ghidra.log" >&2; exit "$status"; }

grep -F 'MMM BP STATUS3 EDGE' "$WORK/ghidra.log"
grep -F 'MMM BP STATUS3 PCODE PASS' "$WORK/ghidra.log"
