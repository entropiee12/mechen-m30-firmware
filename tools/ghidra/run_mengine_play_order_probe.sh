#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:-$ROOT/analysis/community/fw/mengine.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
EXPECTED=b569605e9f66ef94023cf82d665ae119f6bbb7ed0c9d8f5e6c5be867ce91be49
ACTUAL=$(shasum -a 256 "$AP" | awk '{print $1}')

if [ "$ACTUAL" != "$EXPECTED" ]; then
  echo "SHA-256 mismatch for $AP: $ACTUAL" >&2
  exit 1
fi

if [ ! -x "$GHIDRA" ]; then
  echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2
  exit 1
fi

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-mengine-play.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

if ! GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" mengineplay \
    -import "$AP" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$AP" \
    -postScript RunMenginePlayOrderProbe.java \
    >"$WORK/run.log" 2>&1; then
  cat "$WORK/run.log" >&2
  exit 1
fi

if ! grep -Fq 'MENGINE PLAY ORDER P-CODE PASS' "$WORK/run.log"; then
  cat "$WORK/run.log" >&2
  exit 1
fi

grep -E 'MENGINE PLAY (CALL|ORDER)' "$WORK/run.log"
