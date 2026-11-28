#!/usr/bin/env bash
# Verify the community decoder-write callback initialization.
# Static Ghidra analysis only; this never patches or executes firmware.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
BASELINE=${1:-$ROOT/analysis/v1.101.12/fw/mengine.ap}
COMMUNITY=${2:-$ROOT/analysis/community/fw/mengine.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}

for image in "$BASELINE" "$COMMUNITY"; do
  [ -f "$image" ] || { echo "missing mengine.ap: $image" >&2; exit 1; }
done
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-decoder-callback.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

run_baseline() {
  mkdir -p "$WORK/baseline/project"
  JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
  GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/baseline/home}" \
    "$GHIDRA" "$WORK/baseline/project" m30decoderbaseline \
    -import "$BASELINE" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$BASELINE" \
    -postScript DumpAt.java 0x60150fec "$WORK/baseline/decomp.c" \
    -postScript DumpWords.java 0x601510c8 0x601510cc \
    -postScript DumpWords.java 0x60151114 0x6015111c \
    -postScript DumpInstructions.java 0x6015102e 8 \
    -postScript DumpInstructions.java 0x60151104 6 \
    >"$WORK/baseline/ghidra.log" 2>&1
}

run_community() {
  mkdir -p "$WORK/community/project"
  JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
  GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/community/home}" \
    "$GHIDRA" "$WORK/community/project" m30decodercommunity \
    -import "$COMMUNITY" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$COMMUNITY" \
    -postScript DumpAt.java 0x60150fec,0x6015110e "$WORK/community/decomp.c" \
    -postScript DumpWords.java 0x601510c8 0x601510cc \
    -postScript DumpWords.java 0x60151114 0x6015111c \
    -postScript DumpInstructions.java 0x6015102e 8 \
    -postScript DumpInstructions.java 0x60151104 6 \
    >"$WORK/community/ghidra.log" 2>&1
}

run_baseline
run_community

for label in baseline community; do
  grep -q 'DumpAt: 60150fec -> FUN_60150fec' "$WORK/$label/ghidra.log"
  grep -q 'WORD address=601510c8 value=0xbfc11ab5' "$WORK/$label/ghidra.log"
done

# Stock sends only the resident pointer in the undocumented 0x2a decoder
# command.  The adjacent tail is zero-filled.
grep -q 'WORD address=60151114 value=0x00000000' "$WORK/baseline/ghidra.log"
grep -q 'WORD address=60151118 value=0x00000000' "$WORK/baseline/ghidra.log"
grep -q 'INSN address=60151032 bytes=26 b2 lw' "$WORK/baseline/ghidra.log"
grep -q 'INSN address=60151034 bytes=46 d9 sw' "$WORK/baseline/ghidra.log"
grep -q 'INSN address=60151104 bytes=00 00' "$WORK/baseline/ghidra.log"

# Community fills the second callback/context slot, branches through a small
# tail loader, and keeps the original command continuation at 0x60151036.
grep -q 'WORD address=60151114 value=0xbfc11ab5' "$WORK/community/ghidra.log"
grep -q 'WORD address=60151118 value=0x6015110f' "$WORK/community/ghidra.log"
grep -q 'INSN address=60151032 bytes=68 10 b 0x60151104' "$WORK/community/ghidra.log"
grep -q 'INSN address=60151104 bytes=04 b2 lw' "$WORK/community/ghidra.log"
grep -q 'INSN address=60151106 bytes=46 d9 sw' "$WORK/community/ghidra.log"
grep -q 'INSN address=60151108 bytes=04 b2 lw' "$WORK/community/ghidra.log"
grep -q 'INSN address=6015110a bytes=47 d9 sw' "$WORK/community/ghidra.log"
grep -q 'INSN address=6015110c bytes=94 17 b 0x60151036' "$WORK/community/ghidra.log"
grep -q 'DumpAt: 6015110e -> FUN_6015110e' "$WORK/community/ghidra.log"
grep -q 'return 0xffffffff' "$WORK/community/decomp.c"

grep -q 'uStack_20 = _UNK_60151114' "$WORK/community/decomp.c"
grep -q 'uStack_1c = _UNK_60151118' "$WORK/community/decomp.c"
grep -q '0x2a,&uStack_20' "$WORK/community/decomp.c"

echo 'DECODER CALLBACK PROBE PASS baseline=single-pointer community=rejecting-mips16-stub'
