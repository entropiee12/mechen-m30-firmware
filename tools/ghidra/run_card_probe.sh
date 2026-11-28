#!/usr/bin/env bash
# Decompile the seeded SD-card adapter path. Static analysis only.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/card.drv}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}

if [ -z "${JAVA_HOME:-}" ] && command -v brew >/dev/null 2>&1; then
  JAVA_PREFIX=$(brew --prefix openjdk@25 2>/dev/null || true)
  JAVA_HOME="$JAVA_PREFIX/libexec/openjdk.jdk/Contents/Home"
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  export JAVA_HOME
fi

[ -f "$DRIVER" ] || { echo "missing SD adapter driver: $DRIVER" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

PYTHONPATH="$ROOT/tools/emu" python3 "$ROOT/tools/emu/card_binary_probe.py" "$DRIVER"

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-card.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project" "$WORK/home"

# card.drv has a resident segment and a second controller segment. Automatic
# prologue discovery is incomplete, so the entry call tree is seeded explicitly.
set +e
JAVA_TOOL_OPTIONS="-Duser.home=$WORK/home" \
  "$GHIDRA" "$WORK/project" m30card \
  -import "$DRIVER" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript MapSegments.java 2 \
    "$DRIVER" 0xbfc17200 0x200 0x9d0 1 \
    "$DRIVER" 0xbfc27400 0xc00 0xa10 1 \
  -postScript MarkFunction.java \
    0xbfc28000,0xbfc28074,0xbfc283f8,0xbfc28454,0xbfc28494,0xbfc2869c,0xbfc28824,0xbfc28918 \
  -postScript DumpAt.java \
    0xbfc28000,0xbfc28074,0xbfc283f8,0xbfc28454,0xbfc28494,0xbfc2869c,0xbfc28824,0xbfc28918 \
    "$WORK/card.c" 2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"

for address in bfc28000 bfc28074 bfc283f8 bfc28454 bfc28494 bfc2869c bfc28824 bfc28918; do
  grep -q "DumpAt: $address -> FUN_$address" "$WORK/ghidra.log"
done
grep -q '0x55aa' "$WORK/card.c"
grep -q '0xd' "$WORK/card.c"
grep -q '0x400' "$WORK/card.c"
echo 'SD ADAPTER DRIVER PASS seeded-functions=8 reset=0x55aa block-io=present capacity=present'
