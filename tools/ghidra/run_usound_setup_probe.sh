#!/usr/bin/env bash
# Execute the real USB Audio setup function with synthetic MMIO/module returns.
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

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-usound-setup.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30usoundsetup \
    -import "$DRIVER" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript FlatLoad.java "$DRIVER" 0xbfc2fe00 0x200 0x26b0 \
    -postScript RunUsoundSetupProbe.java "$DRIVER" \
    >"$WORK/run.log" 2>&1 || {
      cat "$WORK/run.log" >&2
      exit 1
    }

for marker in \
  'USOUND SETUP TRAP module=0x0f args=(a0=0x00000014,a1=0xbfc3125d,a2=0x00000000,a3=0x00000014)' \
  'USOUND SETUP CALLBACK BRIDGE PASS registered=0xbfc31115 producer=0xbfc30dc4' \
  'USOUND SETUP P-CODE PASS entry=0xbfc322e4 steps='; do
  if ! grep -Fq "$marker" "$WORK/run.log"; then
    echo "missing Ghidra log marker: $marker" >&2
    cat "$WORK/run.log" >&2
    exit 1
  fi
done
grep 'USOUND SETUP TRAP' "$WORK/run.log"
grep 'USOUND SETUP CALLBACK BRIDGE PASS' "$WORK/run.log"
grep 'USOUND SETUP P-CODE PASS' "$WORK/run.log"
