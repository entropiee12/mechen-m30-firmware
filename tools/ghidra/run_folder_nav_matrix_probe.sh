#!/usr/bin/env bash
# Execute real browser/music folder handlers across list and page boundaries.
# This is a read-only Ghidra p-code regression probe; it never writes firmware.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-folder-matrix.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}"
GHIDRA_JAVA_OPTIONS="$GHIDRA_JAVA_OPTIONS -Dapplication.cachedir=$WORK/cache -Dapplication.tempdir=$WORK/tmp"
export GHIDRA_JAVA_OPTIONS
mkdir -p "$WORK/home" "$WORK/cache" "$WORK/tmp"

for app in browser music; do
  AP="$ROOT/analysis/community/fw/$app.ap"
  [ -f "$AP" ] || { echo "missing AP: $AP" >&2; exit 1; }
  mkdir -p "$WORK/$app/project"
  set +e
  JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
    "$GHIDRA" "$WORK/$app/project" "m30foldermatrix-$app" \
    -import "$AP" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$AP" \
    -postScript RunFolderNavMatrix.java "$app" >"$WORK/$app/ghidra.log" 2>&1
  STATUS=$?
  set -e
  if [ "$STATUS" -ne 0 ]; then
    cat "$WORK/$app/ghidra.log" >&2
    exit "$STATUS"
  fi
  grep -q "FOLDER NAV MATRIX PASS app=$app cases=14" "$WORK/$app/ghidra.log"
  grep 'FOLDER NAV MATRIX PASS app=.* mode=' "$WORK/$app/ghidra.log" \
    | sed -E 's/^.*(FOLDER NAV MATRIX PASS app=.*)$/\1/'
done

echo "FOLDER NAV MATRIX PASS cases=28"
