#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:-$ROOT/analysis/community/fw/music.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}
EXPECTED_SHA256=662e379602707a9c4b0dcd6b94b591dd77c2b5f7a557fcb03b8c2a5c9b1baac3

[ -f "$AP" ] || { echo "missing music.ap: $AP" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
[ -x "$JAVA_HOME_OVERRIDE/bin/java" ] || { echo "missing Java runtime: $JAVA_HOME_OVERRIDE" >&2; exit 1; }
actual_sha256=$(shasum -a 256 "$AP" | awk '{print $1}')
[ "$actual_sha256" = "$EXPECTED_SHA256" ] || {
  echo "unexpected music.ap SHA-256: $actual_sha256" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-music-b0-handoff.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

if ! JAVA_HOME="$JAVA_HOME_OVERRIDE" JAVA_TOOL_OPTIONS="-Duser.home=$WORK/home" \
  "$GHIDRA" "$WORK/project" musicb0handoff \
    -import "$AP" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$AP" \
    -postScript RunMusicB0App10HandoffProbe.java "$AP" \
    >"$WORK/run.log" 2>&1; then
  cat "$WORK/run.log" >&2
  exit 1
fi

if ! grep -q 'MUSIC B0 APP10 HANDOFF MATRIX PASS ' "$WORK/run.log"; then
  cat "$WORK/run.log" >&2
  echo "music 0xb0 handoff probe did not report PASS" >&2
  exit 1
fi

grep -E 'MUSIC B0 EVENT|MUSIC B0 HANDOFF PASS|MUSIC B0 APP10 HANDOFF MATRIX PASS' "$WORK/run.log"
