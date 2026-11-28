#!/usr/bin/env bash
# Execute the candidate NORMBREC MIPS32 -> MIPS16 entry path in Ghidra p-code.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
NORM=${1:-$ROOT/analysis/community/afi/NORMBREC.BIN}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
EXPECTED_SHA256=dacb549733c6af1727ed2dd8efe091ff6386ef3a111329f5407475ee620c5ace

[ -f "$NORM" ] || { echo "missing NORMBREC.BIN: $NORM" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
actual_sha=$(shasum -a 256 "$NORM" | awk '{print $1}')
[ "$actual_sha" = "$EXPECTED_SHA256" ] || {
  echo "NORMBREC SHA-256 $actual_sha != $EXPECTED_SHA256" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-normboot.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30normboot \
    -import "$NORM" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript RunNormBootPathProbe.java "$NORM" \
    >"$WORK/run.log" 2>&1 || {
      cat "$WORK/run.log" >&2
      exit 1
    }

if ! grep -F 'NORMBREC PATH P-CODE PASS' "$WORK/run.log"; then
  cat "$WORK/run.log" >&2
  exit 1
fi
if ! grep -F 'NORMBREC PATH LIMIT no-AFI-load-address no-bootloader-or-device-claim' "$WORK/run.log"; then
  cat "$WORK/run.log" >&2
  exit 1
fi
