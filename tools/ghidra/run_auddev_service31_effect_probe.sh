#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$REPO_ROOT/analysis/community/fw/aud_dev.drv}
EXPECTED_SHA256=3349287198faaa90a503353673674d5628e85711db9ee16217db5a803484f09c
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}

[ -f "$DRIVER" ] || { echo "missing audio-device driver: $DRIVER" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
actual_sha256=$(shasum -a 256 "$DRIVER" | awk '{print $1}')
[ "$actual_sha256" = "$EXPECTED_SHA256" ] || {
  echo "aud_dev.drv SHA-256 $actual_sha256 != $EXPECTED_SHA256" >&2
  exit 1
}

PROBE_DIR=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-auddev-service31.XXXXXX")
trap 'rm -rf "$PROBE_DIR"' EXIT
mkdir -p "$PROBE_DIR/home" "$PROBE_DIR/project"

if ! JAVA_HOME="$JAVA_HOME_OVERRIDE" \
  JAVA_TOOL_OPTIONS="-Duser.home=$PROBE_DIR/home" \
  "$GHIDRA" "$PROBE_DIR/project" auddevservice31 \
    -import "$DRIVER" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$REPO_ROOT/tools/ghidra" \
    -postScript MapSegments.java 1 "$DRIVER" 0x7500 0x1f00 0x600 1 \
    -postScript RunAuddevService31EffectProbe.java "$DRIVER" \
    >"$PROBE_DIR/run.log" 2>&1; then
  cat "$PROBE_DIR/run.log" >&2
  exit 1
fi

if ! grep -Fq 'AUDDEV SERVICE-31 EFFECT PROBE COMPLETE' "$PROBE_DIR/run.log"; then
  cat "$PROBE_DIR/run.log" >&2
  exit 1
fi

grep 'AUDDEV SERVICE-31 EFFECT ' "$PROBE_DIR/run.log"
