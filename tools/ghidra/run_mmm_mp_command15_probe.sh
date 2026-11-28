#!/usr/bin/env bash
# Execute mmm_mp command 0x15/0x16 in Ghidra p-code with a synthetic resident copy/lock boundary.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AL=${1:-$ROOT/analysis/community/fw/mmm_mp.al}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}

if [ -z "${JAVA_HOME:-}" ] && command -v brew >/dev/null 2>&1; then
  JAVA_PREFIX=$(brew --prefix openjdk@25 2>/dev/null || true)
  JAVA_HOME="$JAVA_PREFIX/libexec/openjdk.jdk/Contents/Home"
fi
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  export JAVA_HOME
fi

[ -f "$AL" ] || { echo "missing mmm_mp.al: $AL" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
ACTUAL_SHA256=$(shasum -a 256 "$AL" | awk '{print $1}')
[ "$ACTUAL_SHA256" = 57b541ad791637e30135db6c114da98d5680b5f0907ffabf74bf57fa4977a199 ] || {
  echo "unexpected mmm_mp.al SHA-256: $ACTUAL_SHA256" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-mmm-mp-cmd15.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project" "$WORK/home"

set +e
JAVA_TOOL_OPTIONS="-Duser.home=$WORK/home" \
  "$GHIDRA" "$WORK/project" m30mmmmpcmd15 \
  -import "$AL" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript LoadAP.java "$AL" \
  -postScript RunMmmMpCommand15Probe.java 2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"

grep -q 'LoadAP: blocks=14 prologue functions=.* entry=bfc13864' "$WORK/ghidra.log"
grep -q 'MMM_MP COMMAND 15 PASS .*payload_bytes=12 copy_calls=1' "$WORK/ghidra.log"
grep -q 'MMM_MP COMMAND 16 PASS .*payload_bytes=12 copy_calls=1' "$WORK/ghidra.log"
grep -q 'MMM_MP COMMAND 0x15/0x16 PAIR PASS meaning=unresolved resident_copy=synthetic' "$WORK/ghidra.log"

echo 'MMM_MP COMMAND 0x15/0x16 PCODE RUN PASS command semantics remain unnamed'
