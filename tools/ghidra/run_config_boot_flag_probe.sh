#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
CONFIG_BIN=${1:-$ROOT/analysis/community/fw/config.bin}
COMMUNITY=${2:-$ROOT/analysis/community/fw/config.ap}
STOCK=${3:-$ROOT/analysis/stock/fw/config.ap}

[ -f "$CONFIG_BIN" ] || { echo "missing config.bin: $CONFIG_BIN" >&2; exit 1; }
[ -f "$COMMUNITY" ] || { echo "missing community config.ap: $COMMUNITY" >&2; exit 1; }
[ -f "$STOCK" ] || { echo "missing stock config.ap: $STOCK" >&2; exit 1; }
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

verify_hash "$CONFIG_BIN" 900f814711f50446802ee36bcc2d1560df55b9d2fb4e2d964bb35004517b6d19
verify_hash "$COMMUNITY" 5b5687c00880e691500c7abbfba43dab51ae0f95d2203f74aced2e8d56357580
verify_hash "$STOCK" 118c7f7af839901b803e331da19359ddf842fd956b4186290c727eb31bbc5470

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-config-boot-flag.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/community-project" "$WORK/stock-project"

run_variant() {
  local path=$1
  local variant=$2
  local project="$WORK/$variant-project"
  local log="$WORK/$variant.log"
  if ! GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
    "$GHIDRA" "$project" "configboot$variant" \
      -import "$path" \
      -loader BinaryLoader \
      -processor 'MIPS:LE:32:default' \
      -noanalysis \
      -scriptPath "$ROOT/tools/ghidra" \
      -postScript LoadAP.java "$path" \
      -postScript RunConfigBootFlagProbe.java "$CONFIG_BIN" "$variant" "$path" \
      >"$log" 2>&1; then
    cat "$log" >&2
    return 1
  fi
  if ! grep -q "CONFIG BOOT FLAG P-CODE PASS variant=$variant " "$log"; then
    cat "$log" >&2
    echo "config boot flag probe did not report PASS for $variant" >&2
    return 1
  fi
  grep -E 'CONFIG SERVICE TARGETS|CONFIG INIT CASE|CONFIG SENDER CASE|CONFIG BOOT FLAG P-CODE PASS' "$log"
}

run_variant "$COMMUNITY" community
run_variant "$STOCK" stock
