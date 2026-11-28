#!/usr/bin/env bash
# Execute setting.ap confirmation/event paths with resident UI APIs intercepted.
# Synthetic key events only change the host-side emulation state.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:-$ROOT/analysis/community/fw/setting.ap}
INPUT=${2:-1}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-${JAVA_HOME:-}}
WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-setting-confirm.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

[ -f "$AP" ] || { echo "missing setting AP: $AP" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
case "$INPUT" in
  0|1|2|3|4|5|6|7|8|9) ;;
  *) echo "input must be 0 (event boundary), 1 (confirmation request), 2-7 (synthetic event-map entries), 8 (alternate event source), or 9 (continue confirmation through cleanup with saved flag=1)" >&2; exit 1 ;;
esac
if [ -n "$JAVA_HOME_OVERRIDE" ]; then
  export JAVA_HOME="$JAVA_HOME_OVERRIDE"
fi
mkdir -p "$WORK/project" "$WORK/home"
LOG="$WORK/ghidra.log"

set +e
GHIDRA_JAVA_OPTIONS="-Duser.home=$WORK/home" \
  "$GHIDRA" "$WORK/project" m30settingconfirm \
    -import "$AP" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$AP" \
    -postScript RunSettingConfirmProbe.java "$INPUT" 2>&1 | tee "$LOG"
STATUS=${PIPESTATUS[0]}
set -e

[ "$STATUS" -eq 0 ] || exit "$STATUS"
if grep -q 'ERROR REPORT SCRIPT ERROR' "$LOG"; then
  echo "Ghidra script reported an error; probe is not considered a pass" >&2
  exit 1
fi
if [ "$INPUT" -eq 0 ]; then
  grep -q 'SETTING EVENT POLL BOUNDARY PASS' "$LOG"
elif [ "$INPUT" -eq 1 ]; then
  grep -q 'SETTING CONFIRM REQUEST PASS' "$LOG"
elif [ "$INPUT" -eq 8 ]; then
  grep -q 'SETTING ALTERNATE EVENT SOURCE BOUNDARY args=0x00000000,0x10002f98,0x00000000,0x00000019 buffer=00 00 00 00 00 00 00 00' "$LOG"
elif [ "$INPUT" -eq 2 ] || [ "$INPUT" -eq 3 ]; then
  grep -q 'SETTING SYNTHETIC EVENT ABI TRAP BOUNDARY selector=0x00000010 args=0x00000000,0x10002ea8,0x00000003,0x0000000a ra=0x48a549c3' "$LOG"
elif [ "$INPUT" -eq 4 ] || [ "$INPUT" -eq 5 ] || [ "$INPUT" -eq 7 ]; then
  grep -q 'SETTING SYNTHETIC EVENT FOLLOW-UP SERVICE BOUNDARY args=0x00000001,0x00000000,0x00000000,0x00000031' "$LOG"
else
  if [ "$INPUT" -eq 9 ]; then
    grep -q 'SETTING CONFIRM RESTORE PASS steps=' "$LOG"
  else
    grep -q 'SETTING EVENT DISPATCH ENTER code=' "$LOG"
    grep -q 'SETTING SYNTHETIC EVENT RETURN PASS code=0x00000108' "$LOG"
  fi
fi
