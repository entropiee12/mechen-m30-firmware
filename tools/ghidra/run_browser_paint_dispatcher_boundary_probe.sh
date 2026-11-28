#!/usr/bin/env bash
# Verify the browser's in-AP row/list dispatch boundary and absence of direct
# pointers to the extracted drv_ui marquee/render candidates.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:-$ROOT/analysis/community/fw/browser.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
EXPECTED_SHA256=10b0e5d8ed725a76ebce0bfa6e843427115d55b59216fd461a22703e3fed34b3

[ -f "$AP" ] || { echo "missing browser.ap: $AP" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
ACTUAL=$(shasum -a 256 "$AP" | awk '{print $1}')
[ "$ACTUAL" = "$EXPECTED_SHA256" ] || { echo "unexpected browser.ap SHA-256: $ACTUAL" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-browser-dispatch-boundary.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project" "$WORK/scripts"
cp "$ROOT/tools/ghidra/LoadAP.java" "$ROOT/tools/ghidra/RunBrowserPaintDispatcherBoundaryProbe.java" "$WORK/scripts/"

if ! GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30browserPaintBoundary \
    -import "$AP" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$WORK/scripts" \
    -postScript LoadAP.java "$AP" \
    -postScript RunBrowserPaintDispatcherBoundaryProbe.java \
    >"$WORK/run.log" 2>&1; then
  cat "$WORK/run.log" >&2
  exit 1
fi
python3 - "$AP" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
data = path.read_bytes()
targets = (0x260E4FF5, 0x26409401, 0x164490A1)
for target in targets:
    needle = target.to_bytes(4, "little")
    offset = data.find(needle)
    if offset >= 0:
        raise SystemExit(f"unexpected raw browser.ap pointer 0x{target:08x} at file offset 0x{offset:x}")
print("BROWSER RAW FILE POINTER SCAN PASS values=3 offsets=all-bytes")
PY
grep -F 'BROWSER PAINT DISPATCHER BOUNDARY PASS' "$WORK/run.log"
