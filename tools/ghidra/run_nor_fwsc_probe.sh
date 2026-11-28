#!/usr/bin/env bash
# Execute the real NORFWSC result-return tail in Ghidra p-code.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
NORFWSC=${1:?usage: run_nor_fwsc_probe.sh NORFWSC.BIN}
GHIDRA=${GHIDRA:-$(command -v analyzeHeadless || true)}
NORFWSC_SHA256=5b72dee96b608139baea2a8d187e8c8bc4644600c0c085382093f9f2a95fb920

[ -f "$NORFWSC" ] || { echo "missing NORFWSC: $NORFWSC" >&2; exit 1; }
[ -n "$GHIDRA" ] && [ -x "$GHIDRA" ] || {
  echo "analyzeHeadless not found; install Ghidra or set GHIDRA to its executable path" >&2
  exit 1
}
actual_sha=$(shasum -a 256 "$NORFWSC" | awk '{print $1}')
[ "$actual_sha" = "$NORFWSC_SHA256" ] || {
  echo "NORFWSC SHA-256 $actual_sha != $NORFWSC_SHA256" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-norfwsc.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project" "$WORK/javatmp"

GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home -Djava.io.tmpdir=$WORK/javatmp}" \
  "$GHIDRA" "$WORK/project" m30norfwsc \
    -import "$NORFWSC" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript MapNorFwsc.java "$NORFWSC" \
    -postScript RunNorFwscReturnProbe.java \
    >"$WORK/run.log" 2>&1 || {
      cat "$WORK/run.log" >&2
      exit 1
    }

grep 'NORFWSC RETURN P-CODE PASS' "$WORK/run.log"
grep -q 'MapNorFwsc: bytes=0x2a00 .*base=0xbfc1e000 entry=0xbfc1e200 zero-prefix=0x200' "$WORK/run.log"
