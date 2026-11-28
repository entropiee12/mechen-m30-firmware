#!/usr/bin/env bash
# Decompile the resident SD-card controller boundary. Static analysis only.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/cards.drv}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}

if [ -z "${JAVA_HOME:-}" ] && command -v brew >/dev/null 2>&1; then
  JAVA_PREFIX=$(brew --prefix openjdk@25 2>/dev/null || true)
  JAVA_HOME="$JAVA_PREFIX/libexec/openjdk.jdk/Contents/Home"
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  export JAVA_HOME
fi

[ -f "$DRIVER" ] || { echo "missing SD-card driver: $DRIVER" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

PYTHONPATH="$ROOT/tools/emu" python3 "$ROOT/tools/emu/cards_binary_probe.py" "$DRIVER"

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-cards.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project" "$WORK/home"

# file 0x200 is loaded at 0xbfc28000, so file byte zero is bfc27e00.
set +e
JAVA_TOOL_OPTIONS="-Duser.home=$WORK/home" \
  "$GHIDRA" "$WORK/project" m30cards \
  -import "$DRIVER" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript FlatLoad.java "$DRIVER" 0xbfc27e00 0x200 0x1400 \
  -postScript DumpC.java "$WORK/cards.c" 2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"

grep -q 'FlatLoad: bytes=5120 functions=23' "$WORK/ghidra.log"
for address in bfc280c0 bfc2816c bfc28430 bfc28564 bfc28d20 bfc28d60 bfc28f50 bfc290d8 bfc29200; do
  grep -q "FUN_$address" "$WORK/cards.c"
done
grep -q '0x200' "$WORK/cards.c"
grep -q '0x55aa' "$WORK/cards.c"
grep -q '0xd' "$WORK/cards.c"
echo 'SD CARD DRIVER PASS functions=23 block-io=present detect-capacity=present'
