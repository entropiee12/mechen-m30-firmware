#!/usr/bin/env bash
# Execute the stock UDisk AP caller up to its fixed ROM callback.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:?usage: run_udisk_fixed_callback_probe.sh STOCK_UDISK.AP}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
EXPECTED_SHA256=0cc4c679d956f56e29d9fd3f9d5fe53e2134b072db07eba8e542468d63a56548

[ -f "$AP" ] || { echo "missing stock udisk.ap: $AP" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
actual_sha=$(shasum -a 256 "$AP" | awk '{print $1}')
[ "$actual_sha" = "$EXPECTED_SHA256" ] || {
  echo "stock udisk.ap SHA-256 mismatch: $actual_sha" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-udisk-callback.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project" "$WORK/home"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:--Duser.home=$WORK/home -Djava.io.tmpdir=$WORK}"
if [ -z "${JAVA_HOME:-}" ] && command -v brew >/dev/null 2>&1; then
  JAVA_PREFIX=$(brew --prefix openjdk@25 2>/dev/null || true)
  JAVA_HOME="$JAVA_PREFIX/libexec/openjdk.jdk/Contents/Home"
fi
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  export JAVA_HOME
fi

GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home -Djava.io.tmpdir=$WORK}" \
  "$GHIDRA" "$WORK/project" m30udiskfixedcallback \
    -import "$AP" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$AP" \
    -postScript RunUdiskFixedCallbackPcodeProbe.java \
    >"$WORK/run.log" 2>&1 || {
      cat "$WORK/run.log" >&2
      exit 1
    }

if ! grep -F 'UDISK FIXED CALLBACK P-CODE PASS' "$WORK/run.log"; then
  cat "$WORK/run.log" >&2
  exit 1
fi
grep -F 'UDISK FIXED CALLBACK EDGE' "$WORK/run.log"
grep -F 'UDISK FIXED CALLBACK STATE' "$WORK/run.log"
