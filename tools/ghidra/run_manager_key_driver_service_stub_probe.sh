#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
MANAGER=${1:-$ROOT/analysis/community/fw/manager.ap}
CONFIG=${2:-$ROOT/analysis/community/fw/config.bin}
EXPECTED_SHA256=d94d2406d5e88efee1ffacff2d399ce7d865410ac7458efdb9f185eae359af7a
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}

[ -f "$MANAGER" ] || { echo "missing manager.ap: $MANAGER" >&2; exit 1; }
[ -f "$CONFIG" ] || { echo "missing config.bin: $CONFIG" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
actual_sha256=$(shasum -a 256 "$MANAGER" | awk '{print $1}')
[ "$actual_sha256" = "$EXPECTED_SHA256" ] || {
  echo "manager.ap SHA-256 $actual_sha256 != $EXPECTED_SHA256" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-manager-key-service-stub.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

if ! JAVA_HOME="$JAVA_HOME_OVERRIDE" \
  JAVA_TOOL_OPTIONS="-Duser.home=$WORK/home" \
  "$GHIDRA" "$WORK/project" managerkeyservicestub \
    -import "$MANAGER" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$MANAGER" \
    -postScript RunManagerKeyDriverServiceStubProbe.java "$MANAGER" "$CONFIG" \
    >"$WORK/run.log" 2>&1; then
  cat "$WORK/run.log" >&2
  exit 1
fi

if ! grep -Fq 'MANAGER KEY-DRIVER SERVICE-STUB COMPLETE' "$WORK/run.log"; then
  cat "$WORK/run.log" >&2
  echo "manager key-driver service-stub probe did not report completion" >&2
  exit 1
fi
if ! grep -Fq 'MANAGER INIT SERVICE TRACE PASS' "$WORK/run.log"; then
  cat "$WORK/run.log" >&2
  echo "manager init service-trace probe did not report completion" >&2
  exit 1
fi
grep -E 'MANAGER (KEY-DRIVER SERVICE-STUB (PASS|COMPLETE)|INIT SERVICE TRACE PASS)' "$WORK/run.log"
