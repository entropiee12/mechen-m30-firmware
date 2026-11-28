#!/usr/bin/env bash
# Execute the real browser/music folder-list handler in Ghidra's p-code.
# LCD redraw helpers are intercepted; no firmware image is modified.
set -euo pipefail

[ $# -ge 1 ] || { echo "usage: $0 [browser|music] [next|previous] [total page start end cursor]" >&2; exit 2; }
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
APP=$1
MODE=${2:-next}
shift 2 || true
case "$APP" in
  browser|music) AP="$ROOT/analysis/community/fw/$APP.ap" ;;
  *) echo "app must be browser or music" >&2; exit 2 ;;
esac
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
[ -f "$AP" ] || { echo "missing AP: $AP" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-folder-pcode.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project"

set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" "m30folderpcode-$APP" \
  -import "$AP" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript LoadAP.java "$AP" \
  -postScript RunFolderNavProbe.java "$MODE" "$@" 2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"
grep -q 'FOLDER NAV PASS' "$WORK/ghidra.log"
