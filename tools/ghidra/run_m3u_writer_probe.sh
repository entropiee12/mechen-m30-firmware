#!/usr/bin/env bash
# Verify the M3U.LIB scan, record writer, and text-conversion boundaries.
# Static Ghidra analysis only; no firmware or library is modified.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
PLAYLIST=${1:-$ROOT/analysis/community/fw/playlist.ap}
OUTPUT_DIR=${2:-$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-m3u-writer.XXXXXX")}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}

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
  "$GHIDRA" "$OUTPUT_DIR/project" m30m3uwriter \
  -import "$PLAYLIST" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript LoadAP.java "$PLAYLIST" \
  -postScript DumpAt.java \
    0x40154ecc,0x40154cd8,0x40154dc0,0x480145dc,0x48014694 \
    "$OUTPUT_DIR/m3u-writer.c" containing \
  -postScript DumpWords.java 0x4801455c 0x480145dc \
  -postScript DumpWords.java 0x48014690 0x480146d4 \
    >"$OUTPUT_DIR/run.log" 2>&1

for address in \
  '40154ecc -> FUN_40154ecc' \
  '40154cd8 -> FUN_40154cd8' \
  '40154dc0 -> FUN_40154dc0' \
  '480145dc -> FUN_480145dc' \
  '48014694 -> FUN_48014694'; do
  grep -q "DumpAt: $address" "$OUTPUT_DIR/run.log"
done

# The scanner consumes the filesystem walker twice and routes each item
# through the record/location writer before the 0x100-byte block write.
grep -q 'FUN_40154cd8(uStack_c)' "$OUTPUT_DIR/m3u-writer.c"
grep -q 'FUN_40154dc0(\*_UNK_40154fbc)' "$OUTPUT_DIR/m3u-writer.c"
grep -q '+ 0xa4' "$OUTPUT_DIR/m3u-writer.c"
grep -q '+ 0xa9' "$OUTPUT_DIR/m3u-writer.c"
grep -q '+ 0xf4' "$OUTPUT_DIR/m3u-writer.c"
grep -q '+ 0xfc' "$OUTPUT_DIR/m3u-writer.c"
grep -q '+ 0xfe' "$OUTPUT_DIR/m3u-writer.c"
grep -q '0x100' "$OUTPUT_DIR/m3u-writer.c"

# The shared key converter has distinct UTF-16LE BOM and UTF-8 BOM paths.
grep -q 'FUN_480145dc' "$OUTPUT_DIR/m3u-writer.c"
grep -q '\-0x101' "$OUTPUT_DIR/m3u-writer.c"
grep -q '\-0x11' "$OUTPUT_DIR/m3u-writer.c"

# Resolve the indirect slots instead of calling them an unknown transform:
# module 6 handles text conversion and module 0x10 supplies memcpy/memset.
grep -q 'address=4801455c value=0xbfc16bb5' "$OUTPUT_DIR/run.log"
grep -q 'address=480145d4 value=0xbfc16bb5' "$OUTPUT_DIR/run.log"
grep -q 'address=480145d8 value=0xbfc16409' "$OUTPUT_DIR/run.log"
grep -q 'address=48014690 value=0xbfc16409' "$OUTPUT_DIR/run.log"
grep -q 'address=480146d0 value=0xbfc16409' "$OUTPUT_DIR/run.log"

echo "M3U WRITER GHIDRA PASS output=$OUTPUT_DIR"
