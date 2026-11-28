#!/usr/bin/env bash
# Reproduce the browser name-buffer and copy-limit findings from browser.ap.
# Static analysis only; this does not execute or modify firmware.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:-$ROOT/analysis/community/fw/browser.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}

[ -f "$AP" ] || { echo "missing browser AP: $AP" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-browser-names.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project"

set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30browsernames \
  -import "$AP" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript LoadAP.java "$AP" \
  -postScript DumpWords.java 0x487546b8 0x487546d4 \
  -postScript DumpWords.java 0x71352a68 0x71352a74 \
  -postScript DumpAt.java \
    0x40cd4e00,0x48754574,0x71352820,0x714d2658,0x780db22c \
    "$WORK/decomp.c" 2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"

grep -q 'LoadAP: blocks=75 ' "$WORK/ghidra.log"
for marker in \
  'DumpAt: 40cd4e00 -> FUN_40cd4e00' \
  'DumpAt: 48754574 -> FUN_48754574' \
  'DumpAt: 71352820 -> FUN_71352820' \
  'DumpAt: 714d2658 -> FUN_714d2658' \
  'DumpAt: 780db22c -> FUN_780db22c'; do
  grep -q "$marker" "$WORK/ghidra.log"
done

# The directory callback's name read goes through the resident filesystem
# helper, with the destination/length pair taken from each 0x32-byte slot.
# Keep the literal-pointer chain checked separately from decompiler variable
# names, which can change between Ghidra releases.
for marker in \
  'WORD address=487546b8 value=0x9fc15598' \
  'WORD address=487546bc value=0x9fc155a4' \
  'WORD address=487546c0 value=0x780db201' \
  'WORD address=71352a68 value=0xbfc16a2d' \
  'WORD address=71352a70 value=0xbfc16a21'; do
  grep -q "$marker" "$WORK/ghidra.log"
done

# Directory mode uses 0x32-byte records.  Library mode copies at most 0x40
# bytes from a title field, then appends a two-byte terminator.  Keep the
# checks against decompiler output so changes in the loader or AP layout fail
# loudly instead of silently weakening the finding.
grep -q 'bStack_10 \* 0x32' "$WORK/decomp.c"
grep -q 'uStack_38 = 0x40' "$WORK/decomp.c"
grep -q 'uVar15 = \*(byte \*)(param_2 + 1) - 2' "$WORK/decomp.c"
grep -q '\*(undefined1 \*)(param_2\[2\] + uVar15) = 0' "$WORK/decomp.c"
grep -q '\*(undefined1 \*)(param_2\[2\] + uVar15 + 1) = 0' "$WORK/decomp.c"

echo 'BROWSER NAME PROBE PASS directory-slot=0x32 fs-helper=0xbfc16a2d library-cap=0x40'
