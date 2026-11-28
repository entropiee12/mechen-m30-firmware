#!/usr/bin/env bash
# Replay the real M30 USB descriptor patcher with synthetic config buffers.
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

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-usound-descriptor.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30usounddescriptor \
    -import "$DRIVER" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript FlatLoad.java "$DRIVER" 0xbfc2fe00 0x200 0x26b0 \
    -postScript RunUsoundDescriptorPatchProbe.java "$DRIVER" \
    >"$WORK/run.log" 2>&1 || {
      cat "$WORK/run.log" >&2
      exit 1
    }

for marker in \
  'FlatLoad: bytes=9904 functions=46' \
  'USOUND DESCRIPTOR P-CODE PASS case=stock-config vid=10d6 pid=dd01 interfaces=3 format=0' \
  'USOUND DESCRIPTOR P-CODE PASS case=24bit-96k vid=10d6 pid=dd01 interfaces=3 format=1' \
  'USOUND DESCRIPTOR P-CODE PASS case=interface-flag-clear vid=10d6 pid=dd01 interfaces=1 format=0' \
  'USOUND DESCRIPTOR P-CODE PASS case=empty-vid-pid vid=10d6 pid=dd00 interfaces=3 format=0' \
  'USOUND DESCRIPTOR P-CODE PASS case=serial-unavailable vid=10d6 pid=dd01 interfaces=3 format=0' \
  'USOUND FW INFO TRACE status=0x00 calls=1 serial-buffer=0x00000000 serial-descriptor-bytes=0 iSerialNumber=3' \
  'USOUND DESCRIPTOR P-CODE PASS case=serial-status-one vid=10d6 pid=dd01 interfaces=3 format=0' \
  'USOUND FW INFO TRACE status=0x01 calls=2 serial-buffer=0x9fc33d12 serial-descriptor-bytes=34 iSerialNumber=3' \
  'USOUND DESCRIPTOR P-CODE PASS case=serial-status-three vid=10d6 pid=dd01 interfaces=3 format=0' \
  'USOUND FW INFO TRACE status=0x03 calls=1 serial-buffer=0x00000000 serial-descriptor-bytes=0 iSerialNumber=3' \
  'USOUND DESCRIPTOR P-CODE PASS case=serial-empty vid=10d6 pid=dd01 interfaces=3 format=0' \
  'USOUND FW INFO TRACE status=0x02 calls=2 serial-buffer=0x9fc33d12 serial-descriptor-bytes=18 iSerialNumber=0'; do
  if ! grep -Fq "$marker" "$WORK/run.log"; then
    echo "missing Ghidra log marker: $marker" >&2
    cat "$WORK/run.log" >&2
    exit 1
  fi
done

grep -E 'USOUND FW INFO (CALL|TRACE)' "$WORK/run.log" || true
echo "USOUND DESCRIPTOR PATCH PROBE PASS cases=10 real-mips16=1 synthetic-config=1 rom-stubs=2"
