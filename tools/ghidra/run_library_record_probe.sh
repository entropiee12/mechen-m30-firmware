#!/usr/bin/env bash
# Verify the playlist record/location ABI and fallback track-number parser boundary.
# Static Ghidra analysis only; no firmware or library is modified.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
PLAYLIST=${1:-$ROOT/analysis/community/fw/playlist.ap}
EXPECTED_SHA256=184f2c201537e6657024a2a5a054d12fe4a7f134e06205f0786a8d71d2a29403
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
actual_sha256=$(shasum -a 256 "$PLAYLIST" | awk '{print $1}')
[ "$actual_sha256" = "$EXPECTED_SHA256" ] || {
  echo "playlist.ap SHA-256 $actual_sha256 != $EXPECTED_SHA256" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-library-record.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30libraryrecord \
  -import "$PLAYLIST" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript LoadAP.java "$PLAYLIST" \
  -postScript DumpAt.java 0x40094c8c,0x40094d60,0x40094f2c,0x40094fec,0x48014588,0x480145dc,0x48014694 "$WORK/fallback.c" containing \
  -postScript DumpWords.java 0x40094fe8 0x40094fec \
  -postScript DumpWords.java 0x480145d8 0x480145dc \
  -postScript DumpWords.java 0x48014690 0x48014694 \
  -postScript DumpWords.java 0x480146d0 0x480146d4 \
  >"$WORK/run.log" 2>&1

grep -q 'DumpAt: 40094c8c -> FUN_40094c8c' "$WORK/run.log"
grep -q 'DumpAt: 40094d60 -> FUN_40094d60' "$WORK/run.log"
grep -q 'DumpAt: 40094f2c -> FUN_40094f2c' "$WORK/run.log"
grep -q 'DumpAt: 40094fec -> FUN_40094fec' "$WORK/run.log"
grep -q 'DumpAt: 48014588 -> FUN_48014588' "$WORK/run.log"
grep -q 'DumpAt: 480145dc -> FUN_480145dc' "$WORK/run.log"
grep -q 'DumpAt: 48014694 -> FUN_48014694' "$WORK/run.log"
grep -q 'WORD address=40094fe8 value=0xbfc1640d' "$WORK/run.log"
grep -q 'WORD address=480145d8 value=0xbfc16409' "$WORK/run.log"
grep -q 'WORD address=48014690 value=0xbfc16409' "$WORK/run.log"
grep -q 'WORD address=480146d0 value=0xbfc16409' "$WORK/run.log"
grep -q "param_1\[bStack_10\] = '.'" "$WORK/fallback.c"
grep -q 'bStack_f = 9' "$WORK/fallback.c"
grep -q 'bStack_10 < 4' "$WORK/fallback.c"
grep -q "_UNK_40094f24\[bStack_10\] != '/'" "$WORK/fallback.c"
grep -q '0x2f < (byte)_UNK_40094f24' "$WORK/fallback.c"
grep -q 'bStack_f = bStack_f \*' "$WORK/fallback.c"
grep -q 'undefined2.*= 0xffff' "$WORK/fallback.c"
grep -q '+ 0xa9' "$WORK/fallback.c"
grep -q '+ 0xf4' "$WORK/fallback.c"
grep -Fq 'FUN_480145dc(param_2,param_3,param_4);' "$WORK/fallback.c"
grep -Fq '(*_UNK_480145d8)(param_1,_UNK_480145d0,param_2,0);' "$WORK/fallback.c"
grep -Fq '(*_UNK_48014690)(_UNK_4801468c,param_1 + 1,(uint)param_3 << 1,0);' "$WORK/fallback.c"
grep -Fq '(*_UNK_480146d0)(param_1,_UNK_480146cc,param_4,0);' "$WORK/fallback.c"

echo 'LIBRARY RECORD GHIDRA PASS location-callback=0xbfc1640d op=3 short-name=FUN_40094c8c track-prefix=4-digits fallback=0xffff'
echo 'MODULE10 COPY-CALLSITE PASS callers=FUN_48014588,FUN_480145dc,FUN_48014694 target=0xbfc16409 selector=0'
