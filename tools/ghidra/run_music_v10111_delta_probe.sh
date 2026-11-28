#!/usr/bin/env bash
# Classify the two non-EOF music.ap deltas shared by V1.101.11/community.
# Static/decompiler-only; this never patches firmware or creates a HEX image.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
STOCK=${1:-$ROOT/analysis/stock/fw/music.ap}
DRAIN=${2:-$ROOT/analysis/v1.101.11/fw/music.ap}
COMMUNITY=${3:-$ROOT/analysis/community/fw/music.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}

for image in "$STOCK" "$DRAIN" "$COMMUNITY"; do
  [ -f "$image" ] || { echo "missing music.ap: $image" >&2; exit 1; }
done
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-music-delta.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

decompile() {
  local label=$1
  local image=$2
  mkdir -p "$WORK/$label/project"
  JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
  GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/$label/home}" \
    "$GHIDRA" "$WORK/$label/project" "m30musicdelta-$label" \
    -import "$image" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$image" \
    -postScript DumpAt.java \
      0x7815b5cc,0x781db3c1,0x714125ac,0x71612644 \
      "$WORK/$label/decomp.c" containing \
    >"$WORK/$label/run.log" 2>&1
  grep -q 'DumpAt: 781db3c1 -> FUN_781db3c0 (containing) @ 781db3c0' \
    "$WORK/$label/run.log"
  grep -q 'DumpAt: 714125ac -> FUN_714125ac' "$WORK/$label/run.log"
  grep -q 'DumpAt: 71612644 -> FUN_71612644' "$WORK/$label/run.log"
  grep -q 'DumpAt: 7815b5cc -> FUN_7815b5cc' "$WORK/$label/run.log"
  grep -q 'FUN_781db3c0' "$WORK/$label/decomp.c"
}

decompile stock "$STOCK"
decompile drain "$DRAIN"
decompile community "$COMMUNITY"

# The 0x120c0-0x12193 replacement is a shared metadata/file-reader helper.
# Stock has one boundary split; V1.101.11/community loop over 0x200-byte
# boundaries and account for the bytes actually consumed in each iteration.
! grep -q 'while (uVar5 != 0)' "$WORK/stock/decomp.c"
grep -q 'while (uVar5 != 0)' "$WORK/drain/decomp.c"
grep -q 'while (uVar5 != 0)' "$WORK/community/decomp.c"
grep -q 'param_2 <= param_3' "$WORK/drain/decomp.c"
grep -q 'param_2 <= param_3' "$WORK/community/decomp.c"
grep -q 'FUN_781db3c0' "$WORK/drain/decomp.c"
grep -q 'FUN_781db3c0' "$WORK/community/decomp.c"

# Raw guards cover the small sibling change and the start of the large helper
# replacement.  The decompiler checks above cover the control-flow meaning.
[ "$(xxd -p -s 0x115cc -l 3 "$STOCK" | tr -d '\n')" = e12625 ]
[ "$(xxd -p -s 0x115cc -l 3 "$DRAIN" | tr -d '\n')" = 112575 ]
[ "$(xxd -p -s 0x115cc -l 3 "$COMMUNITY" | tr -d '\n')" = 112575 ]
[ "$(xxd -p -s 0x120c0 -l 4 "$STOCK" | tr -d '\n')" = d6640601 ]
[ "$(xxd -p -s 0x120c0 -l 4 "$DRAIN" | tr -d '\n')" = f9641d67 ]
[ "$(xxd -p -s 0x120c0 -l 4 "$COMMUNITY" | tr -d '\n')" = f9641d67 ]

echo 'MUSIC V10111 DELTA PROBE PASS reader=metadata-buffer-loop sibling=opaque'
