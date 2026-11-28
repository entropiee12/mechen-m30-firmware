#!/usr/bin/env bash
# Reproduce the browser literal table that binds the list buffer and path_info.
# Static analysis only; this does not execute or modify firmware.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:-$ROOT/analysis/community/fw/browser.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}

[ -f "$AP" ] || { echo "missing browser AP: $AP" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-browser-layout.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project" "$WORK/home"

set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30browserlayout \
  -import "$AP" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript LoadAP.java "$AP" \
  -postScript DumpWords.java 0x40cd500c 0x40cd5050 \
  -postScript DumpAt.java 0x40cd4e00 "$WORK/decomp.c" 2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"

grep -q 'LoadAP: blocks=75 ' "$WORK/ghidra.log"
grep -q 'DumpAt: 40cd4e00 -> FUN_40cd4e00' "$WORK/ghidra.log"
for marker in \
  'WORD address=40cd5024 value=0x40cd4c00' \
  'WORD address=40cd5040 value=0x40cd4d90' \
  'WORD address=40cd5028 value=0x48754575' \
  'WORD address=40cd5044 value=0x487546d9'; do
  grep -q "$marker" "$WORK/ghidra.log"
done
grep -q 'UNK_40cd5028)(_UNK_40cd5020,_UNK_40cd5024,param_2)' "$WORK/decomp.c"
grep -q 'UNK_40cd5044)(_UNK_40cd5020,_UNK_40cd5040)' "$WORK/decomp.c"
grep -q 'UNK_40cd501c)(&uStack_20,_UNK_40cd5040' "$WORK/decomp.c"

python3 - <<'PY' "$WORK/ghidra.log"
import re
import sys

log = open(sys.argv[1], encoding="utf-8").read()
words = {
    int(address, 16): int(value, 16)
    for address, value in re.findall(
        r"WORD address=([0-9a-f]+) value=0x([0-9a-f]+)", log
    )
}
assert words[0x40cd5024] == 0x40cd4c00
assert words[0x40cd5040] == 0x40cd4d90
assert 0x40cd5040 - 0x40cd5024 == 0x1c
span = words[0x40cd5040] - words[0x40cd5024]
assert span == 8 * 0x32
print(
    "BROWSER LAYOUT PASS "
    f"list-buffer=0x{words[0x40cd5024]:08x} "
    f"path-info=0x{words[0x40cd5040]:08x} "
    f"literal-distance=0x{0x40cd5040 - 0x40cd5024:x} "
    f"slot-span=0x{span:x}"
)
PY
