#!/usr/bin/env bash
# Verify the real M30 EQ dispatch, table layout, and custom-preset boundary.
# Static/decompiler-only; this never patches firmware or creates a HEX image.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
MENGINE=${1:-$ROOT/analysis/community/fw/mengine.ap}
SETTING=${2:-$ROOT/analysis/community/fw/setting.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}

[ -f "$MENGINE" ] || { echo "missing mengine.ap: $MENGINE" >&2; exit 1; }
[ -f "$SETTING" ] || { echo "missing setting.ap: $SETTING" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-eq.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30eq \
  -import "$MENGINE" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript LoadAP.java "$MENGINE" \
  -postScript DumpAt.java 0x601d0d58 "$WORK/decomp.c" containing \
  -postScript DumpWords.java 0x601d0ee8 0x601d0f18 \
  >"$WORK/run.log" 2>&1

grep -q 'DumpAt: 601d0d58 -> FUN_601d0d58 @ 601d0d58' "$WORK/run.log"
grep -q 'bVar1 == 7' "$WORK/decomp.c"
grep -q 'bVar1 \* 7' "$WORK/decomp.c"
grep -q 'bStack_20 < 7' "$WORK/decomp.c"
grep -q 'WORD address=601d0ef4 value=0x601d10ec' "$WORK/run.log"
grep -q 'WORD address=601d0ef8 value=0x601d10fc' "$WORK/run.log"
grep -q 'WORD address=601d0efc value=0x9fc1a19c' "$WORK/run.log"
grep -q 'WORD address=601d0f00 value=0x601d110c' "$WORK/run.log"

eq_table=$(xxd -p -s 0x440c -l 0x31 "$MENGINE" | tr -d '\n')
eq_freqs=$(xxd -p -s 0x43ec -l 0x20 "$MENGINE" | tr -d '\n')
custom_row=$(xxd -p -s 0x5b04 -l 16 "$SETTING" | tr -d '\n')
[ "$eq_table" = 00000000000000050300ff0004050300000000fffe00000102000101030000ff000204000000fffffefc05020000000006 ]
[ "$eq_freqs" = 5000c800e001d403a00f401f803e00000a000600040006000a00050008000000 ]
[ "$custom_row" = f7000000000006000000000000000000 ]

echo 'EQ PROBE PASS bands=7 presets=0..7 custom=0x9fc1a19c bounds=not-observed'
