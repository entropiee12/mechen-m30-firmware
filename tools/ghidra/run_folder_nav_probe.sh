#!/usr/bin/env bash
# Verify the real folder-list next/previous handlers before applying the
# navigation-acceleration blob.  Static Ghidra/decompiler check only.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}

[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-folder-nav.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

for app in browser music; do
  AP="$ROOT/analysis/community/fw/$app.ap"
  [ -f "$AP" ] || { echo "missing AP: $AP" >&2; exit 1; }
  mkdir -p "$WORK/$app/project"

  set +e
  JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
  GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/$app/home}" \
    "$GHIDRA" "$WORK/$app/project" "m30foldernav-$app" \
    -import "$AP" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$AP" \
    -postScript DumpAt.java 0x487144a8,0x487146cc "$WORK/$app/decomp.c" \
    2>&1 | tee "$WORK/$app/ghidra.log"
  STATUS=${PIPESTATUS[0]}
  set -e
  [ "$STATUS" -eq 0 ] || exit "$STATUS"

  grep -q 'DumpAt: 487144a8 -> FUN_487144a8' "$WORK/$app/ghidra.log"
  grep -q 'DumpAt: 487146cc -> FUN_487146cc' "$WORK/$app/ghidra.log"

  # The stock handlers begin with one logical list step and call the stock
  # redraw helper only after updating the descriptor.  These markers are the
  # contract consumed by tools/scroll/accel.c; the probe does not execute it.
  grep -Fq '*_UNK_487146c8 = 1;' "$WORK/$app/decomp.c"
  grep -Fq 'FUN_487148f8(param_1,param_2,*_UNK_487146c8);' "$WORK/$app/decomp.c"
  grep -Fq '*_UNK_487148ac = 1;' "$WORK/$app/decomp.c"
  grep -Fq 'FUN_487148f8(param_1,param_2,*_UNK_487148ac);' "$WORK/$app/decomp.c"
done

echo 'FOLDER NAV PROBE PASS browser/music next=0x487144a8 previous=0x487146cc stock-step=1 redraw=verified'
