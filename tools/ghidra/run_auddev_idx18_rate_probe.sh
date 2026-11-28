#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/aud_dev.drv}
STRUCTURE_HEX=${2:-$ROOT/tools/ghidra/fixtures/auddev_idx18_rock.txt}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
PROBE_SCRIPT=${AUDDEV_PROBE_SCRIPT:-RunAuddevIdx18Rate.java}
PASS_PATTERN=${AUDDEV_PASS_PATTERN:-AUDDEV IDX18 RATE P-CODE PASS cases=6}

verify_hash() {
  local path=$1
  local expected=$2
  local actual
  actual=$(shasum -a 256 "$path" | awk '{print $1}')
  if [ "$actual" != "$expected" ]; then
    echo "SHA-256 mismatch for $path: $actual" >&2
    exit 1
  fi
}

verify_hash "$DRIVER" 3349287198faaa90a503353673674d5628e85711db9ee16217db5a803484f09c
WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-auddev-rate.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"
STRUCTURE="$WORK/rock.bin"
xxd -r -p "$STRUCTURE_HEX" "$STRUCTURE"
verify_hash "$STRUCTURE" 387daf406fc306fef17b9c2251823932a0d2281ab118c9a72e498ada92dc6530

if ! GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" auddevrate \
    -import "$DRIVER" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript MapSegments.java 1 "$DRIVER" 0x7500 0x1f00 0x600 1 \
    -postScript "$PROBE_SCRIPT" "$DRIVER" "$STRUCTURE" \
    >"$WORK/run.log" 2>&1; then
  cat "$WORK/run.log" >&2
  exit 1
fi

if ! grep -Fq "$PASS_PATTERN" "$WORK/run.log"; then
  cat "$WORK/run.log" >&2
  exit 1
fi

grep -E 'COMPARE|RUN |BEFORE|AFTER|equal-to-input|WRITE|P-CODE PASS' "$WORK/run.log"
