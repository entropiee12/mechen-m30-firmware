#!/usr/bin/env bash
# Execute the real USB Disk setup function with synthetic MMIO/module returns.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/udisk.drv}
EXPECTED_SHA256=afc02a4dcb383d6677eedf243448de848a534f8af67fde1508aec6dd672a14a4
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}

[ -f "$DRIVER" ] || { echo "missing USB Disk driver: $DRIVER" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
actual_sha256=$(shasum -a 256 "$DRIVER" | awk '{print $1}')
[ "$actual_sha256" = "$EXPECTED_SHA256" ] || {
  echo "udisk.drv SHA-256 $actual_sha256 != $EXPECTED_SHA256" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-udisk-setup.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30udisksetup \
    -import "$DRIVER" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript FlatLoad.java "$DRIVER" 0xbfc2fe00 0x200 \
    -postScript RunUdiskSetupProbe.java "$DRIVER" \
    >"$WORK/run.log" 2>&1 || {
      cat "$WORK/run.log" >&2
      exit 1
    }

for marker in \
  'UDISK SETUP TRAP module=0x0f args=(a0=0x0000000a,a1=0xbfc30d15,a2=0x00000000,a3=0x00000014)' \
  'UDISK SETUP P-CODE PASS entry=0xbfc32abc steps='; do
  if ! grep -Fq "$marker" "$WORK/run.log"; then
    echo "missing Ghidra log marker: $marker" >&2
    cat "$WORK/run.log" >&2
    exit 1
  fi
done
grep 'UDISK SETUP TRAP' "$WORK/run.log"
grep 'UDISK SETUP P-CODE PASS' "$WORK/run.log"
