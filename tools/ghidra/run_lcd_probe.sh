#!/usr/bin/env bash
# Decompile the resident LCD controller helper. Static analysis only.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/drv_lcd.drv}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}

[ -f "$DRIVER" ] || { echo "missing LCD driver: $DRIVER" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

PYTHONPATH="$ROOT/tools/emu" python3 "$ROOT/tools/emu/lcd_binary_probe.py" "$DRIVER"

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-lcd.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project"

# file 0x200 is loaded at 0xbfc0a000, so file byte zero is bfc09e00.
set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30lcd \
  -import "$DRIVER" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript FlatLoad.java "$DRIVER" 0xbfc09e00 0x200 0x800 \
  -postScript DumpC.java "$WORK/lcd.c" 2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"

grep -q 'FlatLoad: bytes=2048 functions=16' "$WORK/ghidra.log"
grep -q 'FUN_bfc0a378' "$WORK/lcd.c"
grep -q 'FUN_bfc0a438' "$WORK/lcd.c"
grep -q '0x2a' "$WORK/lcd.c"
grep -q '0x2b' "$WORK/lcd.c"
grep -q '0x2c' "$WORK/lcd.c"
grep -q '0x36' "$WORK/lcd.c"
grep -q '0x2e' "$WORK/lcd.c"
echo 'LCD DRIVER PASS functions=16 window=0x2a,0x2b,0x2c orientation=0x36 read=0x2e'
