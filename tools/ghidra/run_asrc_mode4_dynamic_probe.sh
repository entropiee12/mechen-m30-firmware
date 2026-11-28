#!/usr/bin/env bash
# Execute the real music.ap EOF/mode-4 caller against controlled RAM state.
# Resident/UI callbacks are intercepted at their original targets; AP literal
# pools and firmware files are never rewritten.
set -euo pipefail

ROOT=${M30_REPO_ROOT:-$(cd "$(dirname "$0")/../.." && pwd)}
SCRIPT_DIR=${M30_GHIDRA_SCRIPT_DIR:-$ROOT/tools/ghidra}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-asrc-mode4.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
mkdir -p "$WORK/scripts"
cp "$SCRIPT_DIR/LoadAP.java" "$SCRIPT_DIR/RunAsrcMode4DynamicProbe.java" "$WORK/scripts/"

run_case() {
  local label=$1 ap=$2 expected_sha=$3 variant=$4
  local project="$WORK/$label/project" home="$WORK/$label/home" log="$WORK/$label.log"
  local actual_sha
  actual_sha=$(shasum -a 256 "$ap" | awk '{print $1}')
  [ "$actual_sha" = "$expected_sha" ] || { echo "$label music.ap SHA-256 mismatch: $actual_sha" >&2; exit 1; }
  mkdir -p "$project" "$home"
  if [ "$variant" = stock ]; then
    if ! GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$home}" "$GHIDRA" "$project" "m30asrc-$label" \
      -import "$ap" -loader BinaryLoader -processor 'MIPS:LE:32:default' -noanalysis \
      -scriptPath "$WORK/scripts" -postScript LoadAP.java "$ap" \
      -postScript RunAsrcMode4DynamicProbe.java stock >"$log" 2>&1; then cat "$log" >&2; exit 1; fi
    expect_log 'ASRC MODE CASE mode=4 progress=20 start-index=2 end-index=3 advance-args=[] end-notice-args=[0] end-ui-calls=1' "$log"
    expect_log 'ASRC MODE4 DYNAMIC PASS actual-music.ap code; callback literals verified before-and-after; resident/UI boundary stubbed; stock=true' "$log"
  else
    if ! GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$home}" "$GHIDRA" "$project" "m30asrc-$label" \
      -import "$ap" -loader BinaryLoader -processor 'MIPS:LE:32:default' -noanalysis \
      -scriptPath "$WORK/scripts" -postScript LoadAP.java "$ap" \
      -postScript RunAsrcMode4DynamicProbe.java >"$log" 2>&1; then cat "$log" >&2; exit 1; fi
    expect_log 'ASRC MODE CASE mode=4 progress=20 start-index=2 end-index=0 advance-args=[0] end-notice-args=[] end-ui-calls=0' "$log"
    expect_log 'ASRC MODE4 DYNAMIC PASS actual-music.ap code; callback literals verified before-and-after; resident/UI boundary stubbed; stock=false' "$log"
  fi
  expect_log 'ASRC MODE CASE mode=2 progress=20 start-index=2 end-index=0 advance-args=[0]' "$log"
  expect_log 'ASRC MODE CASE mode=4 progress=9 start-index=2 end-index=2 advance-args=[]' "$log"
  rg 'ASRC MODE CASE|ASRC MODE4 DYNAMIC PASS' "$log"
}

expect_log() {
  local marker=$1 log=$2
  grep -Fq "$marker" "$log" || { cat "$log" >&2; exit 1; }
}

run_case stock "$ROOT/analysis/stock/fw/music.ap" \
  faa6b68a68de78ef45c2a9d9ad664b6b6310a93f21f9a07b29467827abd49193 stock
run_case community "$ROOT/analysis/community/fw/music.ap" \
  662e379602707a9c4b0dcd6b94b591dd77c2b5f7a557fcb03b8c2a5c9b1baac3 community

echo 'ASRC MODE4 CORPUS PASS stock-vs-community-end-of-list'
