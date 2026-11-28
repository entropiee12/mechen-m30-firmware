#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AL=${1:-$ROOT/analysis/community/fw/mmm_mp.al}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
EXPECTED=57b541ad791637e30135db6c114da98d5680b5f0907ffabf74bf57fa4977a199
ACTUAL=$(shasum -a 256 "$AL" | awk '{print $1}')

if [ "$ACTUAL" != "$EXPECTED" ]; then
  echo "SHA-256 mismatch for $AL: $ACTUAL" >&2
  exit 1
fi

if [ ! -x "$GHIDRA" ]; then
  echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2
  exit 1
fi

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-mmm-mp-dsp.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

if ! GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" mmmmpdsp \
    -import "$AL" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$AL" \
    -postScript RunMmmMpDspStartProbe.java \
    >"$WORK/run.log" 2>&1; then
  cat "$WORK/run.log" >&2
  exit 1
fi

if ! grep -Fq 'MMM_MP DSP START P-CODE PASS cases=8' "$WORK/run.log"; then
  cat "$WORK/run.log" >&2
  exit 1
fi
if ! grep -Fq 'MMM_MP EQ COMMAND P-CODE PASS' "$WORK/run.log"; then
  cat "$WORK/run.log" >&2
  exit 1
fi
if ! grep -Fq 'MMM_MP PLAY DISPATCH P-CODE PASS' "$WORK/run.log"; then
  cat "$WORK/run.log" >&2
  exit 1
fi
if ! grep -Fq 'MMM_MP PLAY HANDLER P-CODE PASS' "$WORK/run.log"; then
  cat "$WORK/run.log" >&2
  exit 1
fi

grep -E 'MMM_MP (DSP (CASE|START)|EQ COMMAND|PLAY (DISPATCH|HANDLER))' "$WORK/run.log"
