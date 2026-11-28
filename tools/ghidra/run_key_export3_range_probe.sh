#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$REPO_ROOT/analysis/community/fw/key.drv}
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

PROBE_DIR=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-key-slot3.XXXXXX")
trap 'rm -rf "$PROBE_DIR"' EXIT
mkdir -p "$PROBE_DIR/home" "$PROBE_DIR/project"

if ! JAVA_HOME="$JAVA_HOME_OVERRIDE" \
  JAVA_TOOL_OPTIONS="-Duser.home=$PROBE_DIR/home" \
  "$GHIDRA" "$PROBE_DIR/project" keyexport3range \
    -import "$DRIVER" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$REPO_ROOT/tools/ghidra" \
    -postScript FlatLoad.java "$DRIVER" 0x29008400 0x1000 0x600 \
    -postScript RunKeyExport3RangeProbe.java "$DRIVER" \
    >"$PROBE_DIR/run.log" 2>&1; then
  cat "$PROBE_DIR/run.log" >&2
  exit 1
fi

if ! grep -Fq 'KEY EXPORT-3 RANGE PROBE COMPLETE entry=0x29009584 tested=256 matches=28' "$PROBE_DIR/run.log"; then
  cat "$PROBE_DIR/run.log" >&2
  exit 1
fi

grep -F 'KEY EXPORT-3 RANGE PROBE COMPLETE' "$PROBE_DIR/run.log"
