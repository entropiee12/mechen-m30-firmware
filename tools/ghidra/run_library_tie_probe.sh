#!/usr/bin/env bash
# Decompile the stock MUSIC.LIB tie-refinement and compare callbacks.
# Static Ghidra analysis only; no firmware or library is modified or produced.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
PLAYLIST=${1:-$ROOT/analysis/community/fw/playlist.ap}
OUTPUT_DIR=${2:-$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-library-tie.XXXXXX")}
GHIDRA=${GHIDRA:-/opt/homebrew/opt/ghidra/libexec/support/analyzeHeadless}

if [ -z "${JAVA_HOME:-}" ] && command -v brew >/dev/null 2>&1; then
  JAVA_PREFIX=$(brew --prefix openjdk@25 2>/dev/null || true)
  JAVA_HOME="$JAVA_PREFIX/libexec/openjdk.jdk/Contents/Home"
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  export JAVA_HOME
fi

[ -f "$PLAYLIST" ] || { echo "missing playlist.ap: $PLAYLIST" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

mkdir -p "$OUTPUT_DIR/home"
PROJECT_DIR=$(mktemp -d "$OUTPUT_DIR/project.XXXXXX")

JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:--Duser.home=$OUTPUT_DIR/home}" \
  "$GHIDRA" "$PROJECT_DIR" m30librarytie \
  -import "$PLAYLIST" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript LoadAP.java "$PLAYLIST" \
  -postScript DumpAt.java \
    0x40114c00,0x40114d54,0x71812500,0x71812548,0x71812650,0x718126c0,0x71812788,0x718127cc,0x48014534,0x48014588 \
    "$OUTPUT_DIR/tie.c" containing \
    >"$OUTPUT_DIR/run.log" 2>&1

for address in \
  '40114c00 -> FUN_40114c00' \
  '40114d54 -> FUN_40114d54' \
  '71812500 -> FUN_71812500' \
  '71812548 -> FUN_71812548' \
  '71812650 -> FUN_71812650' \
  '718126c0 -> FUN_718126c0' \
  '71812788 -> FUN_71812788' \
  '718127cc -> FUN_718127cc' \
  '48014534 -> FUN_48014534' \
  '48014588 -> FUN_48014588'; do
  grep -q "DumpAt: $address" "$OUTPUT_DIR/run.log"
done

echo "LIBRARY TIE GHIDRA PASS output=$OUTPUT_DIR"
