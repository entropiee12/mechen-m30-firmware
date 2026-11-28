#!/usr/bin/env bash
# Run the real setting AP initializer to its callback writer entry.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:-$ROOT/analysis/community/fw/setting.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
[ -f "$AP" ] || { echo "missing setting AP: $AP" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
sha=$(shasum -a 256 "$AP" | awk '{print $1}')
case "$sha" in
  ad46a92fc0b12f58fdad3a7885502ef5e7481c7b2913fd7e28d46216d22a9ed7|c0693d3750e1b92ad18b0ed741e893e5cffe3bc4517abdc086c54d539e2eb7d5) ;;
  *) echo "unsupported setting.ap SHA-256: $sha" >&2; exit 1 ;;
esac

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-setting-caller.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project" "$WORK/home"
GHIDRA_JAVA_OPTIONS="-Duser.home=$WORK/home" \
  "$GHIDRA" "$WORK/project" m30settingcaller \
    -import "$AP" -loader BinaryLoader -processor 'MIPS:LE:32:default' \
    -noanalysis -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$AP" \
    -postScript RunSettingCallbackCallerProbe.java >"$WORK/run.log" 2>&1 || {
      cat "$WORK/run.log" >&2
      exit 1
    }
if ! grep -Fq 'SETTING CALLBACK CALLER PASS ' "$WORK/run.log" ||
   ! grep -Fq 'SETTING CALLBACK EVENT BRIDGE PASS ' "$WORK/run.log" ||
   ! grep -Fq 'SETTING EVENT CALLBACK RETURN ' "$WORK/run.log"; then
  tail -50 "$WORK/run.log" >&2
  exit 1
fi
grep -F 'SETTING CALLBACK CALLER PASS ' "$WORK/run.log"
grep -F 'SETTING EVENT CALLBACK BUFFER ' "$WORK/run.log"
grep -F 'SETTING CALLBACK EVENT BRIDGE PASS ' "$WORK/run.log"
grep -F 'SETTING EVENT CALLBACK RETURN ' "$WORK/run.log"
echo "SETTING CALLBACK CALLER CORPUS PASS sha256=$sha"
