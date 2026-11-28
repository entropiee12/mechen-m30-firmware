#!/usr/bin/env bash
# Run the real mainmenu status notifier with synthetic key/message services.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
MAINMENU=${1:-$ROOT/analysis/community/fw/mainmenu.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}
EXPECTED_SHA256=1ef9b7a466a0f3a6bec8ce8654422c372fd68105ff94739077bfe34b435a1105

[ -f "$MAINMENU" ] || { echo "missing mainmenu.ap: $MAINMENU" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
actual_sha256=$(shasum -a 256 "$MAINMENU" | awk '{print $1}')
[ "$actual_sha256" = "$EXPECTED_SHA256" ] || {
  echo "mainmenu.ap SHA-256 $actual_sha256 != $EXPECTED_SHA256" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-mainmenu-status-notifier.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

if ! JAVA_HOME="$JAVA_HOME_OVERRIDE" \
  JAVA_TOOL_OPTIONS="-Duser.home=$WORK/home" \
  "$GHIDRA" "$WORK/project" statusnotifier \
    -import "$MAINMENU" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$MAINMENU" \
    -postScript RunMainmenuStatusNotifierProbe.java "$MAINMENU" \
    >"$WORK/run.log" 2>&1; then
  cat "$WORK/run.log" >&2
  exit 1
fi

if ! grep -Fq 'MAINMENU STATUS NOTIFIER P-CODE PASS timer_ticks=360 transitions=3 normalization=2 raw_level=1' "$WORK/run.log"; then
  cat "$WORK/run.log" >&2
  echo "mainmenu status notifier probe did not report PASS" >&2
  exit 1
fi

grep -E 'STATUS NOTIFIER (CASE|TRANSITIONS|NORMALIZATION|RAW LEVEL)|MAINMENU STATUS NOTIFIER P-CODE PASS' "$WORK/run.log"
