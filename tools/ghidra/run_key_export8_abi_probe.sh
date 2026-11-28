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

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-key-slot8.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

if ! JAVA_HOME="$JAVA_HOME_OVERRIDE" \
  JAVA_TOOL_OPTIONS="-Duser.home=$WORK/home" \
  "$GHIDRA" "$WORK/project" keyexport8abi \
    -import "$DRIVER" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript FlatLoad.java "$DRIVER" 0xbfc0fe00 0x200 \
    -postScript RunKeyExport8AbiProbe.java "$DRIVER" \
    >"$WORK/run.log" 2>&1; then
  cat "$WORK/run.log" >&2
  exit 1
fi

if ! grep -Fq 'KEY EXPORT-8 ABI PROBE COMPLETE entry=0xbfc102e4' "$WORK/run.log"; then
  cat "$WORK/run.log" >&2
  exit 1
fi

grep -E 'KEY (EXPORT-8 ABI |EVENT REPEAT )' "$WORK/run.log"
