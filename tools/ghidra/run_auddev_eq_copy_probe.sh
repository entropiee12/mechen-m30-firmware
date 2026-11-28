#!/usr/bin/env bash
# Execute the real aud_dev bank-5 EQ path and capture module-0x10 copy calls.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/aud_dev.drv}
EQ_STRUCTURE=${2:-}
EXPECTATION=${3:-observe}
EXPECTED_SHA256=3349287198faaa90a503353673674d5628e85711db9ee16217db5a803484f09c
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}

[ -f "$DRIVER" ] || { echo "missing audio-device driver: $DRIVER" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
actual_sha256=$(shasum -a 256 "$DRIVER" | awk '{print $1}')
[ "$actual_sha256" = "$EXPECTED_SHA256" ] || {
  echo "aud_dev.drv SHA-256 $actual_sha256 != $EXPECTED_SHA256" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-auddev-eq-copy.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"
script_args=(-postScript RunAuddevEqCopyProbe.java "$DRIVER")
if [ -n "$EQ_STRUCTURE" ]; then
  [ -f "$EQ_STRUCTURE" ] || { echo "missing EQ structure: $EQ_STRUCTURE" >&2; exit 1; }
  script_args+=("$EQ_STRUCTURE" "$EXPECTATION")
fi

GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" auddeveqcopy \
    -import "$DRIVER" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript MapSegments.java 1 "$DRIVER" 0x7500 0x1f00 0x600 1 \
    "${script_args[@]}" \
    >"$WORK/run.log" 2>&1 || {
      cat "$WORK/run.log" >&2
      exit 1
    }

if ! grep -Fq 'AUDDEV EQ COPY P-CODE PASS entry=0x00009400' "$WORK/run.log"; then
  cat "$WORK/run.log" >&2
  exit 1
fi
grep 'AUDDEV EQ COPY band=' "$WORK/run.log"
grep 'AUDDEV EQ COPY P-CODE PASS' "$WORK/run.log"
if [ "$EXPECTATION" = decoded-groups ]; then
  grep 'AUDDEV EQ GROUPS P-CODE PASS flags=+0xc,+7,+9,+0xa,+0xb,+0xd records=3' "$WORK/run.log"
elif [ "$EXPECTATION" = disabled-groups ]; then
  grep 'AUDDEV EQ GROUPS P-CODE PASS flag=+0xc value=0 drc-writes=0' "$WORK/run.log"
fi
