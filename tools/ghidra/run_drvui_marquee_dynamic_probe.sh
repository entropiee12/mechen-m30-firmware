#!/usr/bin/env bash
# Execute extracted drv_ui B3 marquee instructions with UI/ROM providers stubbed.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/drv_ui.drv}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
EXPECTED_SHA256=011f4e6a50738026ac641370f493c5df2e59adc435871d8a213e717604d2576a

[ -f "$DRIVER" ] || { echo "missing drv_ui.drv: $DRIVER" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
ACTUAL=$(shasum -a 256 "$DRIVER" | awk '{print $1}')
[ "$ACTUAL" = "$EXPECTED_SHA256" ] || { echo "unexpected drv_ui.drv SHA-256: $ACTUAL" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-drvui-marquee.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project" "$WORK/scripts"
cp "$ROOT/tools/ghidra/FlatLoad.java" "$ROOT/tools/ghidra/RunDrvUiMarqueeDynamicProbe.java" "$WORK/scripts/"
if ! GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30drvuiMarquee \
    -import "$DRIVER" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$WORK/scripts" \
    -postScript FlatLoad.java "$DRIVER" 0x260db980 0x9674 0x80c \
    -postScript RunDrvUiMarqueeDynamicProbe.java \
    >"$WORK/run.log" 2>&1; then
  cat "$WORK/run.log" >&2
  exit 1
fi
for marker in \
  'DRV_UI MARQUEE CASE mode=0x16 measured-width=80' \
  'width-calls=0 text-helper-calls=5 resident-forwarder-calls=2 transforms=0 far-calls=2' \
  'DRV_UI MARQUEE CASE mode=0x17 measured-width=80' \
  'width-calls=2 text-helper-calls=5 resident-forwarder-calls=2 transforms=4 far-calls=2' \
  'DRV_UI MARQUEE CASE mode=0x17 measured-width=180' \
  'FAR_CALL desc=9fc0cfa8 region=9fc0cfc8 mode=00000000 descbytes=2cbdc09f420020007c000000000000000500140078007800 regionbytes=2d00150078007700' \
  'FAR_CALL desc=9fc0cfa8 region=9fc0cfc8 mode=00000000 descbytes=2cbdc09f420020007c000000000000000500140078007800 regionbytes=0500150078007700' \
  'DRV_UI MARQUEE DYNAMIC PASS actual-B3 code; B16-qualified external target and UI/MMIO providers stubbed'; do
  grep -Fq "$marker" "$WORK/run.log" || { cat "$WORK/run.log" >&2; exit 1; }
done
rg 'DRV_UI MARQUEE CASE|FAR_CALL|DRV_UI MARQUEE DYNAMIC PASS' "$WORK/run.log"
