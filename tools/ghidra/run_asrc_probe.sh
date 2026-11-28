#!/usr/bin/env bash
# Compare the stock and accepted music.ap EOF state branch.
# Static/decompiler probe only; it never patches or writes firmware.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
STOCK_AP=${1:-$ROOT/analysis/stock/fw/music.ap}
DRAIN_AP=${2:-$ROOT/analysis/v1.101.11/fw/music.ap}
COMMUNITY_AP=${3:-$ROOT/analysis/community/fw/music.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}

for image in "$STOCK_AP" "$DRAIN_AP" "$COMMUNITY_AP"; do
  [ -f "$image" ] || { echo "missing music.ap: $image" >&2; exit 1; }
done
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-asrc.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

run_decomp() {
  local label=$1
  local image=$2
  local home="$WORK/$label/home"
  local project="$WORK/$label/project"
  mkdir -p "$home" "$project"
  GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$home}" \
    "$GHIDRA" "$project" "m30asrc-$label" \
    -import "$image" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$image" \
    -postScript DumpAt.java 0x40494f5d "$WORK/$label/decomp.c" containing \
    >"$WORK/$label/run.log" 2>&1
  grep -q 'DumpAt: 40494f5d -> FUN_40494e94 (containing) @ 40494e94' "$WORK/$label/run.log"
  grep -q '11000U' "$WORK/$label/decomp.c"
}

run_decomp stock "$STOCK_AP"
run_decomp drain "$DRAIN_AP"
run_decomp community "$COMMUNITY_AP"

# The accepted drain branch adds an explicit mode-4 fall-through to the
# existing advance path.  Keep both the control-flow and raw-byte guards so a
# later AP replacement cannot silently invalidate the comparison.
if grep -q 'uStack_10 == 4' "$WORK/stock/decomp.c"; then
  echo 'stock unexpectedly contains the mode-4 EOF branch' >&2
  exit 1
fi
grep -q 'uStack_10 == 4' "$WORK/drain/decomp.c"
grep -q 'uStack_10 == 4' "$WORK/community/decomp.c"

stock_bytes=$(xxd -p -s 0x9f5c -l 6 "$STOCK_AP" | tr -d '\n')
drain_bytes=$(xxd -p -s 0x9f5c -l 6 "$DRAIN_AP" | tr -d '\n')
community_bytes=$(xxd -p -s 0x9f5c -l 6 "$COMMUNITY_AP" | tr -d '\n')
[ "$stock_bytes" = 5867015a2261 ]
[ "$drain_bytes" = 246004720a60 ]
[ "$community_bytes" = 246004720a60 ]

echo 'ASRC DRAIN PROBE PASS stock=no-mode4 drain=mode4 community=mode4'
