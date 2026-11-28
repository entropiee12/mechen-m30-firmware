#!/usr/bin/env bash
# Decompile the MUSIC.LIB index passes and assert the recovered node contract.
# Static Ghidra analysis only; no firmware or library is modified or produced.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
PLAYLIST=${1:-$ROOT/analysis/community/fw/playlist.ap}
OUTPUT_DIR=${2:-$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-library-index.XXXXXX")}
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

mkdir -p "$OUTPUT_DIR/home" "$OUTPUT_DIR/project"

JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:--Duser.home=$OUTPUT_DIR/home}" \
  "$GHIDRA" "$OUTPUT_DIR/project" m30libraryindex \
  -import "$PLAYLIST" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript LoadAP.java "$PLAYLIST" \
  -postScript DumpAt.java \
    0x400d5290,0x400d4c00,0x400d4ce0,0x400d4e80,0x400d5064,0x40294f20,0x71812874,0x480145dc \
    "$OUTPUT_DIR/index.c" containing \
    >"$OUTPUT_DIR/run.log" 2>&1

for address in \
  '400d5290 -> FUN_400d5290' \
  '400d4c00 -> FUN_400d4c00' \
  '400d4ce0 -> FUN_400d4ce0' \
  '400d4e80 -> FUN_400d4e80' \
  '400d5064 -> FUN_400d5064' \
  '40294f20 -> FUN_40294f20' \
  '71812874 -> FUN_71812874' \
  '480145dc -> FUN_480145dc'; do
  grep -q "DumpAt: $address" "$OUTPUT_DIR/run.log"
done

# Main pass ordering: All songs, Album, Artist, Genre.
grep -A30 -q 'void FUN_400d5290' "$OUTPUT_DIR/index.c"
grep -A30 -q 'FUN_400d4c00' "$OUTPUT_DIR/index.c"
grep -A30 -q 'FUN_400d4ce0' "$OUTPUT_DIR/index.c"
grep -A30 -q 'FUN_400d4e80' "$OUTPUT_DIR/index.c"
grep -A30 -q 'FUN_400d5064' "$OUTPUT_DIR/index.c"

# Node writer: fixed type, depth, counts, first record, child/parent links,
# and adjacent-node links are all assigned in the decompiled body.
grep -q 'puStack_34\[1\] = param_5' "$OUTPUT_DIR/index.c"
grep -q 'puStack_34 + 4).*uVar1' "$OUTPUT_DIR/index.c"
grep -q 'puStack_34 + 6).*\*puStackX_0' "$OUTPUT_DIR/index.c"
grep -q 'puStack_34 + 10).*0xffff' "$OUTPUT_DIR/index.c"
grep -q 'puStack_34 + 8).*0xffff' "$OUTPUT_DIR/index.c"
grep -q 'puStack_34 + 0xc).*sStack_18' "$OUTPUT_DIR/index.c"
grep -q 'puStack_34 + 0xe).*sStack_18' "$OUTPUT_DIR/index.c"

# The shared sort dispatch selects one of three compare callbacks, and the
# text converter distinguishes UTF-16 BOM, UTF-8 BOM, and plain bytes.
grep -q '_UNK_71812a80' "$OUTPUT_DIR/index.c"
grep -q '_UNK_71812a84' "$OUTPUT_DIR/index.c"
grep -q '_UNK_71812a88' "$OUTPUT_DIR/index.c"
grep -q 'param_3 << 1' "$OUTPUT_DIR/index.c"
grep -q 'param_1\[1\]' "$OUTPUT_DIR/index.c"

echo "LIBRARY INDEX GHIDRA PASS output=$OUTPUT_DIR"
