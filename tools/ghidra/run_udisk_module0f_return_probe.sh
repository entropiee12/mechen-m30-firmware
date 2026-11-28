#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/udisk.drv}
TARGET_CALL_INDEX=${2:-9}
EXPECTED_SHA256=afc02a4dcb383d6677eedf243448de848a534f8af67fde1508aec6dd672a14a4
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}

case "$TARGET_CALL_INDEX" in
  9|10) ;;
  *) echo "target call index must be 9 or 10: $TARGET_CALL_INDEX" >&2; exit 1 ;;
esac

[ -f "$DRIVER" ] || { echo "missing UDisk driver: $DRIVER" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
actual_sha256=$(shasum -a 256 "$DRIVER" | awk '{print $1}')
[ "$actual_sha256" = "$EXPECTED_SHA256" ] || {
  echo "udisk.drv SHA-256 $actual_sha256 != $EXPECTED_SHA256" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-udisk-module0f.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30udiskmodule0f \
    -import "$DRIVER" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript FlatLoad.java "$DRIVER" 0xbfc2fe00 0x200 \
    -postScript RunUdiskModule0fReturnProbe.java "$DRIVER" "$TARGET_CALL_INDEX" \
    >"$WORK/run.log" 2>&1 || {
      cat "$WORK/run.log" >&2
      exit 1
    }

if ! grep -Fq 'UDISK MODULE-0F RETURN SWEEP COMPLETE' "$WORK/run.log"; then
  cat "$WORK/run.log" >&2
  exit 1
fi

grep 'UDISK MODULE-0F RETURN PROFILE' "$WORK/run.log"
grep 'UDISK MODULE-0F RETURN SWEEP COMPLETE' "$WORK/run.log"
