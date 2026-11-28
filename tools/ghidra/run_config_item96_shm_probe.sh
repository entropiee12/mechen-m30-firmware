#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:-$ROOT/analysis/community/fw/config.ap}
CONFIG_BIN=${2:-$ROOT/analysis/community/fw/config.bin}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}

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

verify_hash "$AP" 5b5687c00880e691500c7abbfba43dab51ae0f95d2203f74aced2e8d56357580
verify_hash "$CONFIG_BIN" 900f814711f50446802ee36bcc2d1560df55b9d2fb4e2d964bb35004517b6d19
if [ ! -x "$GHIDRA" ]; then
  echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2
  exit 1
fi

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-config-item96-shm.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

if ! GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" configitem96shm \
    -import "$AP" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$AP" \
    -postScript RunConfigItem96Shm.java "$CONFIG_BIN" \
    >"$WORK/run.log" 2>&1; then
  cat "$WORK/run.log" >&2
  exit 1
fi

if ! grep -Fq 'CONFIG ITEM96 SHM P-CODE PASS cases=0,1,2' "$WORK/run.log"; then
  cat "$WORK/run.log" >&2
  exit 1
fi

grep -E 'config.bin item 0x96|state\+0x18|dae after|CALL|SHM image|CONFIG ITEM96' "$WORK/run.log"
