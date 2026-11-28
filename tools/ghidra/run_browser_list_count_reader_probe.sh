#!/usr/bin/env bash
# Reproduce the browser list-count reader and its callback literal table.
# Static Ghidra/decompiler analysis only; this does not execute firmware.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:-$ROOT/analysis/community/fw/browser.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
COMMUNITY_SHA256=10b0e5d8ed725a76ebce0bfa6e843427115d55b59216fd461a22703e3fed34b3
STOCK_SHA256=b6e25204d0844b3731292016936b327d70c958e8f2ba46c59216c7633eb8f2f4

[ -f "$AP" ] || { echo "missing browser AP: $AP" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
ACTUAL=$(shasum -a 256 "$AP" | awk '{print $1}')
case "$ACTUAL" in
  "$COMMUNITY_SHA256"|"$STOCK_SHA256") ;;
  *) echo "unexpected browser.ap SHA-256: $ACTUAL" >&2; exit 1 ;;
esac

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-browser-count-reader.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project" "$WORK/home"

if ! GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30browserListCountReader \
    -import "$AP" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$AP" \
    -postScript DumpWords.java 0x40094f80 0x40094fc0 \
    -postScript DumpWords.java 0x40cd5014 0x40cd5018 \
    -postScript DumpWords.java 0x48754938 0x4875493c \
    -postScript DumpAt.java 0x40094e08,0x40cd4e00,0x48754574,0x487546d8 "$WORK/decomp.c" \
    -postScript DumpAt.java 0x487546d8 "$WORK/row_callback.c" \
    >"$WORK/run.log" 2>&1; then
  cat "$WORK/run.log" >&2
  exit 1
fi

for marker in \
  'DumpAt: 40094e08 -> FUN_40094e08' \
  'DumpAt: 40cd4e00 -> FUN_40cd4e00' \
  'DumpAt: 48754574 -> FUN_48754574' \
  'DumpAt: 487546d8 -> FUN_487546d8' \
  'WORD address=40cd5014 value=0x9fc155f8' \
  'WORD address=40094f84 value=0x9fc155f8' \
  'WORD address=40094f8c value=0x780db201' \
  'WORD address=40094f90 value=0x780db28d' \
  'WORD address=40094fa8 value=0x780db22d'; do
  grep -Fq "$marker" "$WORK/run.log" || { cat "$WORK/run.log" >&2; exit 1; }
done
grep -Fq 'WORD address=48754938 value=0x9fc155f8' "$WORK/run.log"

grep -Fq 'uStack_10 = *(short *)(_UNK_40094f80 + 0xe) - *(short *)(_UNK_40094f84 + 2);' "$WORK/decomp.c"
grep -Fq '*(undefined2 *)(_UNK_40cd5014 + 2) = 0;' "$WORK/decomp.c"
grep -Fq '(*_UNK_40094f8c)(0,0,auStack_38);' "$WORK/decomp.c"
grep -Fq '(*_UNK_40094f90)(uStack_10);' "$WORK/decomp.c"
grep -Fq '(*_UNK_40094fa8)(&uStack_28,auStack_20);' "$WORK/decomp.c"
grep -Fq '*(ushort *)(_UNK_48754938 + 2)' "$WORK/decomp.c"
grep -Fq 'if ((uint)*(ushort *)(_UNK_48754938 + 2) < (uint)*(ushort *)(param_1 + 8) + (uint)bStack_10)' "$WORK/decomp.c"
grep -Fq 'if ((uint)*_UNK_4875492c + (uint)*(ushort *)(_UNK_48754938 + 2) <' "$WORK/decomp.c"
grep -Fq '0x8000' "$WORK/decomp.c"
grep -Fq '| 0x100' "$WORK/decomp.c"
grep -Fq '0x7d' "$WORK/row_callback.c"
grep -Fq '0x7c' "$WORK/row_callback.c"
grep -Fq '*_UNK_48754930 + 0xe' "$WORK/row_callback.c"

echo 'BROWSER LIST COUNT READER PASS consumer=0x40094e08 state=0x9fc155f8+2 initial-reset=0 indirect-targets=0x780db201,0x780db28d,0x780db22d'
