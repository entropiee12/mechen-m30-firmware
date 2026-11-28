#!/usr/bin/env bash
# Decompile the resident FAT32/exFAT filesystem boundaries. Static analysis only.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
EXFAT=${1:-$ROOT/analysis/community/fw/exfatn.drv}
FAT32=${2:-$ROOT/analysis/community/fw/fat_32n.drv}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}

if [ -z "${JAVA_HOME:-}" ] && command -v brew >/dev/null 2>&1; then
  JAVA_PREFIX=$(brew --prefix openjdk@25 2>/dev/null || true)
  JAVA_HOME="$JAVA_PREFIX/libexec/openjdk.jdk/Contents/Home"
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  export JAVA_HOME
fi

[ -f "$EXFAT" ] || { echo "missing exFAT driver: $EXFAT" >&2; exit 1; }
[ -f "$FAT32" ] || { echo "missing FAT32 driver: $FAT32" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

PYTHONPATH="$ROOT/tools/emu" python3 "$ROOT/tools/emu/filesystem_binary_probe.py" \
  "$EXFAT" "$FAT32" --corpus-root "$ROOT/analysis"

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-filesystem.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

run_one() {
  local driver=$1
  local name=$2
  local expected_functions=$3
  local expected_markers=$4
  local project="$WORK/project-$name"
  local output="$WORK/$name.c"
  mkdir -p "$project" "$WORK/home-$name"

  JAVA_TOOL_OPTIONS="-Duser.home=$WORK/home-$name" \
    "$GHIDRA" "$project" "m30$name" \
      -import "$driver" \
      -loader BinaryLoader \
      -processor 'MIPS:LE:32:default' \
      -noanalysis \
      -scriptPath "$ROOT/tools/ghidra" \
      -postScript FlatLoad.java "$driver" 0xbfc16a00 0x200 0x800 \
      -postScript DumpC.java "$output" 2>&1 | tee "$WORK/$name.log"

  grep -q "FlatLoad: bytes=2048 functions=$expected_functions" "$WORK/$name.log"
  grep -q "DumpC: functions=$expected_functions" "$WORK/$name.log"
  for marker in $expected_markers; do
    grep -q "$marker" "$output"
  done
  grep -q 'uVar4 < 0x20' "$output"
  for opcode in 0x80 0x81 0x82 0x83; do
    grep -q "$opcode" "$output"
  done
  grep -q '0x6e' "$output"
  grep -q '0x70' "$output"
}

run_one "$EXFAT" exfat 4 \
  'FUN_bfc16c84 FUN_bfc16e48 FUN_bfc16f90 FUN_bfc170e0'
run_one "$FAT32" fat32 6 \
  'FUN_bfc16c84 FUN_bfc16cc0 FUN_bfc16d30 FUN_bfc16d94 FUN_bfc16f20 FUN_bfc17068'

echo 'FILESYSTEM GHIDRA PASS exfat-functions=4 fat32-functions=6 traversal=0x80..0x83 extension-loop=31'
