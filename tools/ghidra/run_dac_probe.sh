#!/usr/bin/env bash
# Decompile the resident DAC/codec serial-bus helper. Static analysis only.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/drv_dac.drv}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}

[ -f "$DRIVER" ] || { echo "missing DAC driver: $DRIVER" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

PYTHONPATH="$ROOT/tools/emu" python3 "$ROOT/tools/emu/dac_binary_probe.py" "$DRIVER"

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-dac.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project"

# file 0x200 is loaded at 0xbfc18d00, so file byte zero is bfc18b00.
set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30dac \
  -import "$DRIVER" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript FlatLoad.java "$DRIVER" 0xbfc18b00 0x200 0x800 \
  -postScript DumpC.java "$WORK/dac.c" 2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"

grep -q 'FlatLoad: bytes=2048 functions=27' "$WORK/ghidra.log"
grep -q 'FUN_bfc18f68' "$WORK/dac.c"
grep -q 'FUN_bfc19060' "$WORK/dac.c"
grep -q 'FUN_bfc19114' "$WORK/dac.c"
grep -q 'FUN_bfc19240' "$WORK/dac.c"
grep -q 'FUN_bfc19330' "$WORK/dac.c"
python3 "$ROOT/tools/emu/verify_dac_protocol_shape.py" "$WORK/dac.c"
echo 'DAC DRIVER PASS functions=27 bit-io=present byte-tx-rx=present'
