#!/usr/bin/env bash
# Replay the real M30 SET_INTERFACE handler against a synthetic MMIO window.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/usound.drv}
EXPECTED_SHA256=e593bdd29c2a96d4d745cf1b5cae4da2ee71c2211c551e415209b18f54e600fc
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}

[ -f "$DRIVER" ] || { echo "missing USB Audio driver: $DRIVER" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

actual_sha256=$(shasum -a 256 "$DRIVER" | awk '{print $1}')
[ "$actual_sha256" = "$EXPECTED_SHA256" ] || {
  echo "usound.drv SHA-256 $actual_sha256 != $EXPECTED_SHA256" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-usound-interface.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30usoundinterface \
    -import "$DRIVER" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript FlatLoad.java "$DRIVER" 0xbfc2fe00 0x200 0x26b0 \
    -postScript RunUsoundInterfaceProbe.java "$DRIVER" \
    >"$WORK/run.log" 2>&1 || {
      cat "$WORK/run.log" >&2
      exit 1
    }

for marker in \
  'FlatLoad: bytes=9904 functions=46' \
  'USOUND FREQUENCY P-CODE PASS requested=32000 get-cur=48000' \
  'USOUND FEATURE CONTROL P-CODE PASS request=0x82 value=0xe3a0' \
  'USOUND FEATURE CONTROL P-CODE PASS request=0x83 value=0xfff0' \
  'USOUND FEATURE CONTROL P-CODE PASS request=0x84 value=0x0030' \
  'USOUND CURRENT CONTROL P-CODE PASS entity=0x09 selector=0x0100 bytes=1' \
  'USOUND CURRENT CONTROL P-CODE PASS entity=0x0b selector=0x0100 bytes=1' \
  'USOUND CURRENT CONTROL P-CODE PASS entity=0x09 selector=0x0200 bytes=2' \
  'USOUND CURRENT CONTROL P-CODE PASS entity=0x09 selector=0x0201 bytes=2' \
  'USOUND CURRENT CONTROL P-CODE PASS entity=0x09 selector=0x0202 bytes=2' \
  'USOUND SET_INTERFACE P-CODE PASS alt=1 reset=true' \
  'USOUND SET_INTERFACE P-CODE PASS alt=0 reset=false'; do
  if ! grep -q "$marker" "$WORK/run.log"; then
    echo "missing Ghidra log marker: $marker" >&2
    cat "$WORK/run.log" >&2
    exit 1
  fi
done

echo "USOUND INTERFACE PROBE PASS stock-mips16=1 alt1-resets-ring=1 alt0-preserves-ring=1"
