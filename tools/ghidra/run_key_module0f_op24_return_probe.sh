#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/key.drv}
EXPECTED_SHA256=858d56ff19106fccb75e35552c9cecca99dce0579c7daac5dce376d742f67ba1
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}

[ -f "$DRIVER" ] || { echo "missing key driver: $DRIVER" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
actual_sha256=$(shasum -a 256 "$DRIVER" | awk '{print $1}')
[ "$actual_sha256" = "$EXPECTED_SHA256" ] || {
  echo "key.drv SHA-256 $actual_sha256 != $EXPECTED_SHA256" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-key-op24.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

if ! JAVA_HOME="$JAVA_HOME_OVERRIDE" \
  JAVA_TOOL_OPTIONS="-Duser.home=$WORK/home" \
  "$GHIDRA" "$WORK/project" keymodule0fop24 \
    -import "$DRIVER" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript FlatLoad.java "$DRIVER" 0xbfc0fe00 0x200 0xa00 \
    -postScript RunKeyModule0fOp24ReturnProbe.java "$DRIVER" \
    >"$WORK/run.log" 2>&1; then
  cat "$WORK/run.log" >&2
  exit 1
fi

if ! grep -Fq 'KEY MODULE-0F OP-24 RETURN SWEEP COMPLETE profiles=5' "$WORK/run.log"; then
  cat "$WORK/run.log" >&2
  exit 1
fi

grep 'KEY MODULE-0F OP-24 RETURN PROFILE' "$WORK/run.log"
grep 'KEY MODULE-0F OP-24 RETURN SWEEP COMPLETE' "$WORK/run.log"
