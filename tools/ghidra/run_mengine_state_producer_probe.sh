#!/usr/bin/env bash
# Execute the real music.ap callback path that prepares the 0x304 payload.
# Resident service boundaries are stubbed; this does not boot the player.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
MUSIC=${1:-$ROOT/analysis/community/fw/music.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
EXPECTED_SHA256=662e379602707a9c4b0dcd6b94b591dd77c2b5f7a557fcb03b8c2a5c9b1baac3

[ -f "$MUSIC" ] || { echo "missing music.ap: $MUSIC" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
ACTUAL_SHA256=$(shasum -a 256 "$MUSIC" | awk '{print $1}')
[ "$ACTUAL_SHA256" = "$EXPECTED_SHA256" ] || {
  echo "unexpected music.ap SHA-256: $ACTUAL_SHA256" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-304-producer.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project"

GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30stateproducer \
  -import "$MUSIC" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript LoadAP.java "$MUSIC" \
  -postScript RunMengineStateProducerProbe.java \
  >"$WORK/ghidra.log" 2>&1 || { cat "$WORK/ghidra.log"; exit 1; }

grep -q 'MENGINE STATE PRODUCER DYNAMIC PASS entry=40394f48 message-id=0x304' "$WORK/ghidra.log" || {
  cat "$WORK/ghidra.log"
  exit 1
}
grep 'MENGINE STATE PRODUCER DYNAMIC PASS' "$WORK/ghidra.log"
