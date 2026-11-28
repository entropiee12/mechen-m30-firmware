#!/usr/bin/env bash
# Execute setting.ap's conditional SYSRAM callback-slot writer in host p-code.
# No device or SD-card state is accessed.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:-$ROOT/analysis/community/fw/setting.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-${JAVA_HOME:-}}
WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-setting-callback.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

[ -f "$AP" ] || { echo "missing setting AP: $AP" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
AP_HASH=$(shasum -a 256 "$AP" | awk '{print $1}')
case "$AP_HASH" in
  ad46a92fc0b12f58fdad3a7885502ef5e7481c7b2913fd7e28d46216d22a9ed7|c0693d3750e1b92ad18b0ed741e893e5cffe3bc4517abdc086c54d539e2eb7d5) ;;
  *) echo "unsupported setting.ap SHA-256: $AP_HASH" >&2; exit 1 ;;
esac
echo "SETTING AP SHA-256: $AP_HASH"
if [ -n "$JAVA_HOME_OVERRIDE" ]; then
  export JAVA_HOME="$JAVA_HOME_OVERRIDE"
fi
mkdir -p "$WORK/project" "$WORK/home"
LOG="$WORK/ghidra.log"

set +e
GHIDRA_JAVA_OPTIONS="-Duser.home=$WORK/home" \
  "$GHIDRA" "$WORK/project" m30settingcallback \
    -import "$AP" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$AP" \
    -postScript RunSettingCallbackWriterProbe.java 2>&1 | tee "$LOG"
STATUS=${PIPESTATUS[0]}
set -e

[ "$STATUS" -eq 0 ] || exit "$STATUS"
if grep -q 'ERROR REPORT SCRIPT ERROR' "$LOG"; then
  echo "Ghidra script reported an error; probe is not considered a pass" >&2
  exit 1
fi
grep -q 'SETTING CALLBACK WRITER PASS function=0x48494600 slot=0x9fc15608 callback=0x40054d79 service=intercepted-v0-0 native-mips-mode=true descriptor=synthetic' "$LOG"
