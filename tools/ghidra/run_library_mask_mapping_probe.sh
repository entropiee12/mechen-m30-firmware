#!/usr/bin/env bash
# Verify the resident filesystem driver's high-bit-first extension-mask loop.
# Static Ghidra analysis only; no filesystem or firmware state is modified.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}

[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-library-mask.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

if [ -z "${JAVA_HOME:-}" ] && command -v brew >/dev/null 2>&1; then
  JAVA_PREFIX=$(brew --prefix openjdk@25 2>/dev/null || true)
  JAVA_HOME="$JAVA_PREFIX/libexec/openjdk.jdk/Contents/Home"
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  export JAVA_HOME
fi

verify_driver() {
  local driver=$1
  local name=$2
  local address=$3
  local project="$WORK/project-$name"
  local output="$WORK/$name.c"

  mkdir -p "$project/home" "$project/project"

  JAVA_TOOL_OPTIONS="-Duser.home=$project/home" \
    "$GHIDRA" "$project/project" "m30librarymask-$name" \
      -import "$driver" \
      -loader BinaryLoader \
      -processor 'MIPS:LE:32:default' \
      -noanalysis \
      -scriptPath "$ROOT/tools/ghidra" \
      -postScript FlatLoad.java "$driver" 0xbfc16a00 0x200 0x800 \
      -postScript DumpAt.java "$address" "$output" containing \
      >"$project/run.log" 2>&1

  grep -q "DumpAt: .* -> FUN_" "$project/run.log"
  grep -q 'param_3 << (uVar4 & 0x1f) < 0' "$output"
  grep -q 'uVar3 = \*(uint \*)(uVar4 \* 4 + iStack_20 + -4)' "$output"
  grep -q 'uVar4 < 0x20' "$output"
}

verify_driver "$ROOT/analysis/community/fw/fat_32n.drv" fat32 0xbfc16d94
verify_driver "$ROOT/analysis/community/fw/exfatn.drv" exfat 0xbfc16f90

echo 'LIBRARY MASK GHIDRA PASS slot0=bit31 slot30=bit1 bit0=ignored fat32/exfat=verified'
