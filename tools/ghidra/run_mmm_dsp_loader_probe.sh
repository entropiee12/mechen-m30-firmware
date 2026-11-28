#!/usr/bin/env bash
# Verify the mmm_mp.al format-index -> DSP filename table.
# Static Ghidra analysis only; no payload is executed or modified.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AL=${1:-$ROOT/analysis/community/fw/mmm_mp.al}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}

[ -f "$AL" ] || { echo "missing mmm_mp.al: $AL" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-mmm-dsp.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project"

set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30mmmdsp \
  -import "$AL" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript LoadAP.java "$AL" \
  -postScript DumpAt.java 0x68312d00,0x68312de4 "$WORK/decomp.c" \
  -postScript DumpC.java "$WORK/all.c" \
  -postScript DumpWords.java 0x68312f60 0x68313060 \
  2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"

grep -q 'LoadAP: blocks=14 ' "$WORK/ghidra.log"
grep -q 'DumpAt: 68312d00 -> FUN_68312d00' "$WORK/ghidra.log"
grep -q 'DumpAt: 68312de4 -> FUN_68312de4' "$WORK/ghidra.log"
grep -q 'WORD address=68312f60 value=0x504d6461' "$WORK/ghidra.log"
grep -q 'WORD address=68313030 value=0x69647561' "$WORK/ghidra.log"
grep -q 'WORD address=68313050 value=0x70706461' "$WORK/ghidra.log"

# Keep the decompiler-side selector evidence separate from the raw table
# evidence.  Variable names may change, but the indexed 0x10-byte lookup and
# the table address are the contract we use in the emulator notes.
grep -Eq '\* 0x10.*68312e1c|68312e1c.*\* 0x10' "$WORK/decomp.c"
grep -q 'param_1 + 0x80' "$WORK/decomp.c"

# The selector is a sub-structure populated by the parser, not a host-side
# guess: the parser is called with the +0x80 address and writes its format
# code through the sub-structure's first word.  Keep one concrete PCM branch
# as an anchor while allowing Ghidra's temporary variable names to vary.
grep -Eq 'DumpC: functions=6[89] ' "$WORK/ghidra.log"
grep -q 'FUN_68152d70' "$WORK/all.c"
grep -q 'FUN_6815327c' "$WORK/all.c"
grep -Fq 'FUN_68152d70((int *)(param_1 + 0x80))' "$WORK/all.c"
grep -q 'param_1 + 0x80) = 6' "$WORK/all.c"

echo 'MMM DSP LOADER PASS format-index=param+0x80 stride=0x10 table=0x68312f60'
