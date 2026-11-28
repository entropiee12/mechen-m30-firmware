#!/usr/bin/env bash
# Verify the candidate's Custom EQ source pointer and mapped in-bank curve.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
MUSIC=${1:-$ROOT/analysis/community/fw/music.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}

[ -f "$MUSIC" ] || { echo "missing music.ap: $MUSIC" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-custom-eq.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

if ! GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30customeq \
    -import "$MUSIC" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$MUSIC" \
    -postScript FindPointerRefs.java 0x403d4f00 \
    -postScript DumpWords.java 0x403d4f00 0x403d4f08 \
    -postScript RunCustomEqProducerProbe.java \
    >"$WORK/run.log" 2>&1; then
  cat "$WORK/run.log" >&2
  exit 1
fi

grep -Fq 'POINTER_REF value=0x403d4f00 address=403d4ef8' "$WORK/run.log"
grep -Fq 'POINTER_TOTAL value=0x403d4f00 count=1' "$WORK/run.log"
grep -Fq 'WORD address=403d4f00 value=0xfe000506' "$WORK/run.log"
grep -Fq 'WORD address=403d4f04 value=0x00030400' "$WORK/run.log"
grep -Fq 'CUSTOM EQ PRODUCER P-CODE PASS' "$WORK/run.log"
echo 'CUSTOM EQ CANDIDATE PROBE PASS source-pointer=403d4ef8 curve=403d4f00..403d4f06 refs=1 runtime-copy=pass payload=7,7'
