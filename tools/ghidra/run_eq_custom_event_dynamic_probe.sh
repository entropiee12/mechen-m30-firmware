#!/usr/bin/env bash
# Execute real Custom callback and shared event target with missing services stubbed.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

for MUSIC in "$ROOT/analysis/community/fw/music.ap" "$ROOT/analysis/v1.101.12/fw/music.ap"; do
  [ -f "$MUSIC" ] || { echo "missing music.ap: $MUSIC" >&2; exit 1; }
  ACTUAL=$(shasum -a 256 "$MUSIC" | awk '{print $1}')
  case "$MUSIC" in
    */analysis/community/fw/music.ap) EXPECTED=662e379602707a9c4b0dcd6b94b591dd77c2b5f7a557fcb03b8c2a5c9b1baac3 ;;
    */analysis/v1.101.12/fw/music.ap) EXPECTED=bc6ed94390c21e2182c1f4fa576ba64c486c29f4ce73ca6d5f4ba1e44e2017e3 ;;
  esac
  [ "$ACTUAL" = "$EXPECTED" ] || { echo "unexpected music.ap hash for $MUSIC: $ACTUAL" >&2; exit 1; }

  WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-eq-event.XXXXXX")
  trap 'rm -rf "$WORK"' EXIT
  mkdir -p "$WORK/home" "$WORK/project" "$WORK/scripts"
  cp "$ROOT/tools/ghidra/LoadAP.java" "$ROOT/tools/ghidra/RunEqCustomEventDynamicProbe.java" "$WORK/scripts/"
  if ! GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
    "$GHIDRA" "$WORK/project" m30eqcustomevent \
      -import "$MUSIC" \
      -loader BinaryLoader \
      -processor 'MIPS:LE:32:default' \
      -noanalysis \
      -scriptPath "$WORK/scripts" \
      -postScript LoadAP.java "$MUSIC" \
      -postScript RunEqCustomEventDynamicProbe.java \
      >"$WORK/run.log" 2>&1; then
    cat "$WORK/run.log" >&2
    exit 1
  fi
  grep -Fq 'EQ CUSTOM EVENT DYNAMIC PASS steps=190 helper=1 target=1 type=3 resident-stubs=3 app-stubs=4 result=0x00008014' "$WORK/run.log" || {
    cat "$WORK/run.log" >&2
    exit 1
  }
  echo "EQ CUSTOM EVENT DYNAMIC PASS image=$(basename "$(dirname "$(dirname "$MUSIC")")") result=-0x7fec"
  rm -rf "$WORK"
  trap - EXIT
done
