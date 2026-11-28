#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
MENGINE=${1:-$ROOT/analysis/community/fw/mengine.ap}
CONFIG_BIN=${2:-$ROOT/analysis/community/fw/config.bin}
AUDDEV=${3:-$ROOT/analysis/community/fw/aud_dev.drv}

[ -f "$MENGINE" ] || { echo "missing mengine.ap: $MENGINE" >&2; exit 1; }
[ -f "$CONFIG_BIN" ] || { echo "missing config.bin: $CONFIG_BIN" >&2; exit 1; }
[ -f "$AUDDEV" ] || { echo "missing aud_dev.drv: $AUDDEV" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

verify_hash() {
  local path=$1
  local expected=$2
  local actual
  actual=$(shasum -a 256 "$path" | awk '{print $1}')
  [ "$actual" = "$expected" ] || {
    echo "SHA-256 mismatch for $path: $actual" >&2
    return 1
  }
}

verify_hash "$MENGINE" b569605e9f66ef94023cf82d665ae119f6bbb7ed0c9d8f5e6c5be867ce91be49
verify_hash "$CONFIG_BIN" 900f814711f50446802ee36bcc2d1560df55b9d2fb4e2d964bb35004517b6d19
verify_hash "$AUDDEV" 3349287198faaa90a503353673674d5628e85711db9ee16217db5a803484f09c

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-eq-config-auddev.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"
if ! GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" eqconfigauddev \
    -import "$MENGINE" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$MENGINE" \
    -postScript RunEqConfigDefaultsAuddevProbe.java "$CONFIG_BIN" "$AUDDEV" \
    >"$WORK/run.log" 2>&1; then
  cat "$WORK/run.log" >&2
  exit 1
fi

if ! grep -q 'EQ CONFIG DEFAULTS -> BUILDER -> AUDDEV P-CODE PASS ' "$WORK/run.log"; then
  cat "$WORK/run.log" >&2
  echo "EQ config/defaults probe did not report PASS" >&2
  exit 1
fi

grep -E 'EQ FACTORY|EQ DAE FACTORY|EQ BUILDER CASE|AUDDEV CASE|EQ OFF VS CUSTOM0|EQ PRESET MATRIX|EQ CONFIG DEFAULTS' "$WORK/run.log"
