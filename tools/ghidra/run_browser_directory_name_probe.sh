#!/usr/bin/env bash
# Execute the real directory-name callback with RAM-backed provider stubs.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:-$ROOT/analysis/community/fw/browser.ap}
EXPECTED_SHA256=10b0e5d8ed725a76ebce0bfa6e843427115d55b59216fd461a22703e3fed34b3
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}
WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-browser-directory.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

[ -f "$AP" ] || { echo "missing browser AP: $AP" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
actual_sha256=$(shasum -a 256 "$AP" | awk '{print $1}')
[ "$actual_sha256" = "$EXPECTED_SHA256" ] || {
  echo "browser.ap SHA-256 $actual_sha256 != $EXPECTED_SHA256" >&2
  exit 1
}
run_case() {
  local label=$1
  local current_index=$2
  local start_index=$3
  local row_count=$4
  local read_result=${5:-0}
  local provider_case=${6:-short83}
  local project="$WORK/$label/project"
  local home="$WORK/$label/home"
  local log="$WORK/$label.log"

  mkdir -p "$project" "$home"
  set +e
  JAVA_HOME="$JAVA_HOME_OVERRIDE" \
  JAVA_TOOL_OPTIONS="-Duser.home=$home" \
    "$GHIDRA" "$project" "m30browserdirectory-$label" \
    -import "$AP" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$AP" \
    -postScript RunBrowserDirectoryNameProbe.java \
      "$current_index" "$start_index" "$row_count" "$read_result" "$provider_case" 2>&1 | tee "$log"
  local status=${PIPESTATUS[0]}
  set -e
  [ "$status" -eq 0 ] || exit "$status"
  grep -Fq "BROWSER DIRECTORY NAME PASS read-result=$read_result" "$log"
  grep -Fq "provider-case=$provider_case" "$log"
  grep -Fq "converter-calls=$row_count" "$log"
}

run_case before 0 5 1 0 short83
run_case inside 5 4 3 0 short83
run_case after 8 5 1 0 short83
run_case read-failure 5 5 1 -1 short83
run_case bom-lfn 5 5 1 0 lfn-bom
run_case plain-lfn 5 5 1 0 lfn-plain
echo 'BROWSER DIRECTORY NAME MATRIX PASS cases=index-before,index-inside,index-after,read-failure,lfn-bom,lfn-plain'
