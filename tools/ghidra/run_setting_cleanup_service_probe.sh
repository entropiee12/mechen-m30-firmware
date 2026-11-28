#!/usr/bin/env bash
# Run real cleanup callback bodies to their resident trap boundary with stubbed returns.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:-$ROOT/analysis/community/fw/setting.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-setting-cleanup.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

[ -f "$AP" ] || { echo "missing setting AP: $AP" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
AP_HASH=$(shasum -a 256 "$AP" | awk '{print $1}')
case "$AP_HASH" in
  ad46a92fc0b12f58fdad3a7885502ef5e7481c7b2913fd7e28d46216d22a9ed7|c0693d3750e1b92ad18b0ed741e893e5cffe3bc4517abdc086c54d539e2eb7d5) ;;
  *) echo "unsupported setting.ap SHA-256: $AP_HASH" >&2; exit 1 ;;
esac

mkdir -p "$WORK/project" "$WORK/home"
LOG="$WORK/ghidra.log"
GHIDRA_JAVA_OPTIONS="-Duser.home=$WORK/home" \
  "$GHIDRA" "$WORK/project" setting-cleanup-probe \
    -import "$AP" -loader BinaryLoader -processor 'MIPS:LE:32:default' \
    -noanalysis -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$AP" \
    -postScript RunSettingCleanupServiceProbe.java >"$LOG" 2>&1 || {
      cat "$LOG" >&2
      exit 1
    }
if ! grep -Fq 'SETTING CLEANUP SERVICE PROBE PASS' "$LOG"; then
  tail -80 "$LOG" >&2
  exit 1
fi
grep -F 'SETTING CLEANUP IMPORT15 ' "$LOG"
grep -F 'SETTING CLEANUP IMPORT6 ' "$LOG"
grep -F 'SETTING CLEANUP SERVICE PROBE PASS' "$LOG"
echo "SETTING CLEANUP SERVICE CORPUS PASS sha256=$AP_HASH"
