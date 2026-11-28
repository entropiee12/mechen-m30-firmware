#!/usr/bin/env bash
# Execute the real music tick wrapper and callback in disposable Ghidra projects.
# Only resident snapshot/event services are intercepted; AP code and literals
# remain unchanged.
set -euo pipefail

ROOT=${M30_REPO_ROOT:-$(cd "$(dirname "$0")/../.." && pwd)}
SCRIPT_DIR=${M30_GHIDRA_SCRIPT_DIR:-$ROOT/tools/ghidra}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-asrc-tick.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
mkdir -p "$WORK/scripts"
cp "$SCRIPT_DIR/LoadAP.java" "$SCRIPT_DIR/RunAsrcMusicTickProbe.java" "$WORK/scripts/"

expect_log() {
  local marker=$1 log=$2
  grep -Fq "$marker" "$log" || { cat "$log" >&2; exit 1; }
}

run_case() {
  local label=$1 ap=$2 expected_sha=$3 variant=$4
  local project="$WORK/$label/project" home="$WORK/$label/home" log="$WORK/$label.log"
  local actual_sha
  actual_sha=$(shasum -a 256 "$ap" | awk '{print $1}')
  [ "$actual_sha" = "$expected_sha" ] || { echo "$label music.ap SHA-256 mismatch: $actual_sha" >&2; exit 1; }
  mkdir -p "$project" "$home"
  if [ "$variant" = stock ]; then
    if ! GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$home}" "$GHIDRA" "$project" "m30asrctick-$label" \
      -import "$ap" -loader BinaryLoader -processor 'MIPS:LE:32:default' -noanalysis \
      -scriptPath "$WORK/scripts" -postScript LoadAP.java "$ap" \
      -postScript RunAsrcMusicTickProbe.java stock >"$log" 2>&1; then cat "$log" >&2; exit 1; fi
    expect_log 'CALLSITE STATIC PASS 400d4fda lw v0,0xc4(pc) -> 400d4fdc jalrc v0 literal=400d509c' "$log"
    expect_log 'ASRC TICK DYNAMIC PASS stock=true steps=real-wrapper+literal-load+jalrc+real-mode4 snapshot-calls=2 event-init=1 callback-jalrc-hits=1 call-target=40494e95 handler=0x40494e94 handler-hits=1 entry-from=400d4fdc return-to=400d4fdf' "$log"
    expect_log ' mode=4 final-index=3 advance-ui-args=[] end-notice=[0] end-ui=1 emulated-literal=unchanged' "$log"
  else
    if ! GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$home}" "$GHIDRA" "$project" "m30asrctick-$label" \
      -import "$ap" -loader BinaryLoader -processor 'MIPS:LE:32:default' -noanalysis \
      -scriptPath "$WORK/scripts" -postScript LoadAP.java "$ap" \
      -postScript RunAsrcMusicTickProbe.java >"$log" 2>&1; then cat "$log" >&2; exit 1; fi
    expect_log 'CALLSITE STATIC PASS 400d4fda lw v0,0xc4(pc) -> 400d4fdc jalrc v0 literal=400d509c' "$log"
    expect_log 'ASRC TICK DYNAMIC PASS stock=false steps=real-wrapper+literal-load+jalrc+real-mode4 snapshot-calls=2 event-init=1 callback-jalrc-hits=1 call-target=40494e95 handler=0x40494e94 handler-hits=1 entry-from=400d4fdc return-to=400d4fdf' "$log"
    expect_log ' mode=4 final-index=0 advance-ui-args=[0] end-notice=[] end-ui=0 emulated-literal=unchanged' "$log"
  fi
  rg 'CALLSITE STATIC PASS|ASRC TICK DYNAMIC PASS' "$log"
}

run_case stock "$ROOT/analysis/stock/fw/music.ap" \
  faa6b68a68de78ef45c2a9d9ad664b6b6310a93f21f9a07b29467827abd49193 stock
run_case community "$ROOT/analysis/community/fw/music.ap" \
  662e379602707a9c4b0dcd6b94b591dd77c2b5f7a557fcb03b8c2a5c9b1baac3 community

echo 'ASRC MUSIC TICK CORPUS PASS stock-vs-community-mode4-callback'
