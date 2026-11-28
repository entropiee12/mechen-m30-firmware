#!/usr/bin/env bash
# Trace the known Custom EQ callback/request path while watching its selector byte.
# Ghidra p-code only; no device or firmware writes.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
MUSIC=${1:-$ROOT/analysis/community/fw/music.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
EXPECTED_SHA256=662e379602707a9c4b0dcd6b94b591dd77c2b5f7a557fcb03b8c2a5c9b1baac3

[ -f "$MUSIC" ] || { echo "missing music.ap: $MUSIC" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
actual_sha256=$(shasum -a 256 "$MUSIC" | awk '{print $1}')
[ "$actual_sha256" = "$EXPECTED_SHA256" ] || {
  echo "music.ap SHA-256 $actual_sha256 != $EXPECTED_SHA256" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-eq-selector.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project" "$WORK/scripts"
cp "$ROOT/tools/ghidra/LoadAP.java" \
  "$ROOT/tools/ghidra/RunEqCustomSelectorTrace.java" "$WORK/scripts/"

GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30eqcustomselector \
    -import "$MUSIC" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$WORK/scripts" \
    -postScript LoadAP.java "$MUSIC" \
    -postScript RunEqCustomSelectorTrace.java \
    >"$WORK/run.log" 2>&1 || {
      cat "$WORK/run.log" >&2
      exit 1
    }

grep -Fq 'EQ CUSTOM SELECTOR TRACE PASS callback=0x40414c00 target=0x48ad47b0 request=0x8000 type=3 selector_value_changes=0 event_loop=0 result=-0x7fec' "$WORK/run.log" || {
  echo "Ghidra completed without the expected trace marker; files in work directory:" >&2
  find "$WORK" -maxdepth 4 -type f -print >&2
  cat "$WORK/run.log" >&2
  exit 1
}
grep -E 'EQ SELECTOR|EQ CUSTOM SELECTOR TRACE PASS' "$WORK/run.log"
