#!/usr/bin/env bash
# Dynamically execute the real mmm_mp.al command 0x1c handler with synthetic RAM.
# OS boundary calls are intercepted; no firmware or device state is changed.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AL=${1:-$ROOT/analysis/community/fw/mmm_mp.al}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
EXPECTED_SHA256=57b541ad791637e30135db6c114da98d5680b5f0907ffabf74bf57fa4977a199

[ -f "$AL" ] || { echo "missing mmm_mp.al: $AL" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
ACTUAL_SHA256=$(shasum -a 256 "$AL" | awk '{print $1}')
[ "$ACTUAL_SHA256" = "$EXPECTED_SHA256" ] || {
  echo "unexpected mmm_mp.al SHA-256: $ACTUAL_SHA256" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-mmm-snapshot.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project"

GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30mmmsnapshot \
  -import "$AL" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript LoadAP.java "$AL" \
  -postScript RunMmmCommandSnapshotProbe.java \
  >"$WORK/ghidra.log" 2>&1 || { cat "$WORK/ghidra.log"; exit 1; }

grep -q 'MMM COMMAND SNAPSHOT DYNAMIC PASS entry=bfc13864 command=0x1c copy=0x18 external-service=stubbed-return-0' "$WORK/ghidra.log" || {
  cat "$WORK/ghidra.log"
  exit 1
}
grep 'MMM COMMAND SNAPSHOT DYNAMIC PASS' "$WORK/ghidra.log"
