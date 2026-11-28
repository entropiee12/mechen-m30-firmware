#!/usr/bin/env bash
# Execute the extracted MMM_BP entry/initializer in Ghidra p-code.
# Resident calls are logged and stubbed; their firmware effects are not modeled.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AL=${1:-$ROOT/analysis/community/fw/mmm_bp.al}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}

if [ -z "${JAVA_HOME:-}" ] && command -v brew >/dev/null 2>&1; then
  JAVA_PREFIX=$(brew --prefix openjdk@25 2>/dev/null || true)
  JAVA_HOME="$JAVA_PREFIX/libexec/openjdk.jdk/Contents/Home"
fi
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  export JAVA_HOME
fi

[ -f "$AL" ] || { echo "missing mmm_bp.al: $AL" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
ACTUAL_SHA256=$(shasum -a 256 "$AL" | awk '{print $1}')
[ "$ACTUAL_SHA256" = ab5d98b578406c4e04fb7b546317fbec0d36ce4a7c139ef0922b06bb4b753c5b ] || {
  echo "unexpected mmm_bp.al SHA-256: $ACTUAL_SHA256" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-mmm-bp-init.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project" "$WORK/home"

set +e
JAVA_TOOL_OPTIONS="-Duser.home=$WORK/home" \
  "$GHIDRA" "$WORK/project" m30mmmbpinit \
  -import "$AL" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript LoadAP.java "$AL" \
  -postScript RunMmmBpInitProbe.java 2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"

grep -q 'LoadAP: blocks=5 prologue functions=30 entry=bfc218a8' "$WORK/ghidra.log"
grep -q 'MMM BP INIT PCODE PASS entry=bfc218a8 dispatcher=680a1ed4 init=68061c00' "$WORK/ghidra.log"
grep -q 'init-range=0xec resident-effects=opaque context+0xd0=write-tracked-untouched-under-no-effect-stub' "$WORK/ghidra.log"

echo 'MMM BP INIT PCODE RUN PASS resident effects remain unresolved'
