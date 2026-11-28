#!/usr/bin/env bash
# Map aud_dev bank 5 and decompile the MIPS16 entry at its table target.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/aud_dev.drv}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}

[ -f "$DRIVER" ] || { echo "missing audio-device driver: $DRIVER" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

EXPECTED_SHA256=3349287198faaa90a503353673674d5628e85711db9ee16217db5a803484f09c
ACTUAL_SHA256=$(shasum -a 256 "$DRIVER" | awk '{print $1}')
[ "$ACTUAL_SHA256" = "$EXPECTED_SHA256" ] || {
  echo "unexpected aud_dev.drv SHA-256: $ACTUAL_SHA256" >&2
  exit 1
}

PYTHONPATH="$ROOT/tools/emu" python3 "$ROOT/tools/emu/auddev_binary_probe.py" "$DRIVER"

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-auddev-bank.XXXXXX")
cleanup() {
  if [ "${AUDDEV_KEEP_WORK:-0}" = 1 ]; then
    echo "AUDDEV Ghidra work files: $WORK"
  else
    rm -rf "$WORK"
  fi
}
trap cleanup EXIT
mkdir -p "$WORK/home" "$WORK/project"

# Bank 5 begins at file 0x1f00 and spans to the next bank at 0x2500.
# Its table target is MIPS16 VA 0x9400, so the base is 0x7500.
if ! JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
  GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" auddevbank5 \
    -import "$DRIVER" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript MapSegments.java 1 "$DRIVER" 0x7500 0x1f00 0x600 1 \
    -postScript DumpAt.java 0x9400 "$WORK/bank5.c" \
    >"$WORK/run.log" 2>&1; then
  cat "$WORK/run.log" >&2
  exit 1
fi

grep -q -- '-> 0x00009400 size=0x600 isa=1' "$WORK/run.log"
grep -q 'DumpAt: 00009400 -> FUN_00009400 @ 00009400' "$WORK/run.log"
grep -q 'undefined4 FUN_00009400(uint param_1,int \*param_2)' "$WORK/bank5.c"
grep -q 'param_2 + 4' "$WORK/bank5.c"
grep -q 'iStack_24 < 0x10' "$WORK/bank5.c"
grep -Fq '(*_UNK_000098fc)(_UNK_000098f8 + iStack_24 * 2 + 0x25,piStack_14,8,0);' "$WORK/bank5.c"
grep -Fq 'piStack_14 = piStack_14 + 2;' "$WORK/bank5.c"
grep -q 'param_2 + 0x31' "$WORK/bank5.c"
grep -q 'iVar9 = 0xac44' "$WORK/bank5.c"

echo 'AUDDEV BANK5 GHIDRA PASS file=0x1f00+0x600 entry=0x9400 module-slot=0x0c bank=5 bands=16 source=param+0x10/stride8 destination=state+0x94/stride8 copy-flag=0 rate-fix=44000-to-44100'
