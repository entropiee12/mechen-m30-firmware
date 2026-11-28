#!/usr/bin/env bash
# Verify the music.ap EQ selector producer and its message boundary.
# Static/decompiler-only; this never patches firmware or creates a HEX image.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
MUSIC=${1:-$ROOT/analysis/community/fw/music.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}

[ -f "$MUSIC" ] || { echo "missing music.ap: $MUSIC" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-eq-producer.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30eqproducer \
  -import "$MUSIC" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript LoadAP.java "$MUSIC" \
  -postScript DumpAt.java \
    0x403d4cec,0x403d4d18,0x403d4d44,0x403d4d70,0x403d4d9c,0x403d4dc8,0x403d4df4,0x403d4e20,0x403d4ed0,0x40614f08 \
    "$WORK/decomp.c" \
  >"$WORK/run.log" 2>&1

for address in \
  403d4cec 403d4d18 403d4d44 403d4d70 403d4d9c 403d4dc8 403d4df4 \
  403d4e20 403d4ed0 40614f08; do
  grep -q "DumpAt: $address" "$WORK/run.log"
done

# The common producer forwards the two-byte {eq_type, selector} payload.
grep -Fq 'uStack_10 = param_1' "$WORK/decomp.c"
grep -Fq 'uStack_f = param_2' "$WORK/decomp.c"
grep -Fq 'FUN_40614f08(&uStack_10)' "$WORK/decomp.c"

# The seven built-in choices are emitted as 7,1 .. 7,6; Off is 0,0.
grep -Fq 'uStack_10 = 0;' "$WORK/decomp.c"
grep -Fq 'uStack_f = 0;' "$WORK/decomp.c"
for selector in 1 2 3 4 5 6; do
  grep -Fq "uStack_f = $selector;" "$WORK/decomp.c"
done
grep -Fq 'uStack_10 = 7;' "$WORK/decomp.c"

# Custom writes the seven user gains, then emits selector 7.
grep -Fq '(*_UNK_403d4efc)(_UNK_403d4ef4,_UNK_403d4ef8,7,0)' "$WORK/decomp.c"
grep -Fq 'FUN_403d4e20(7,7)' "$WORK/decomp.c"

# The sender crosses the same 0x303 message boundary traced in mengine.ap.
grep -Fq '(*_UNK_40614f2c)(param_1,0,0x303)' "$WORK/decomp.c"

echo 'EQ PRODUCER PROBE PASS music=0,0+7,1..7,7 message=0x303'
