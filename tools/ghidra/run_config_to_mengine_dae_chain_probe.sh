#!/usr/bin/env bash
# Run config.ap's DAE save and MEngine's flash read plus EQ builder in MIPS16 p-code.
# Each AP is its own Ghidra program; a synthetic flash directory carries the saved blocks.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
FW=${1:-$ROOT/analysis/community/fw}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
CONFIG_AP_SHA256=5b5687c00880e691500c7abbfba43dab51ae0f95d2203f74aced2e8d56357580
CONFIG_BIN_SHA256=900f814711f50446802ee36bcc2d1560df55b9d2fb4e2d964bb35004517b6d19
MENGINE_AP_SHA256=b569605e9f66ef94023cf82d665ae119f6bbb7ed0c9d8f5e6c5be867ce91be49

check_hash() {
  local path=$1 expected=$2 actual
  [ -f "$path" ] || { echo "missing input: $path" >&2; exit 1; }
  actual=$(shasum -a 256 "$path" | awk '{print $1}')
  [ "$actual" = "$expected" ] || { echo "SHA-256 mismatch for $path: $actual" >&2; exit 1; }
}

check_hash "$FW/config.ap" "$CONFIG_AP_SHA256"
check_hash "$FW/config.bin" "$CONFIG_BIN_SHA256"
check_hash "$FW/mengine.ap" "$MENGINE_AP_SHA256"
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

if [ -z "${JAVA_HOME:-}" ] && command -v brew >/dev/null 2>&1; then
  JAVA_PREFIX=$(brew --prefix openjdk@25 2>/dev/null || true)
  JAVA_HOME="$JAVA_PREFIX/libexec/openjdk.jdk/Contents/Home"
fi
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  export JAVA_HOME
fi

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-config-mengine-chain.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/flash"

run_session() {
  local name=$1 member=$2 log=$3
  shift 3
  rm -rf "$WORK/project-$name"
  mkdir -p "$WORK/home-$name" "$WORK/project-$name"
  if ! GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home-$name}" \
    "$GHIDRA" "$WORK/project-$name" "$name" \
      -import "$FW/$member" \
      -loader BinaryLoader \
      -processor 'MIPS:LE:32:default' \
      -noanalysis \
      -scriptPath "$ROOT/tools/ghidra" \
      -postScript LoadAP.java "$FW/$member" \
      "$@" \
      >"$WORK/$log" 2>&1; then
    cat "$WORK/$log" >&2
    exit 1
  fi
}

run_session config config.ap config.log \
  -postScript RunConfigSaveFlashProbe.java "$FW/config.bin" "$WORK/flash"
grep -Fq 'CONFIG SAVE FLASH P-CODE PASS' "$WORK/config.log" || { cat "$WORK/config.log" >&2; exit 1; }

run_session mengine mengine.ap mengine.log \
  -postScript RunMengineFlashBuilderProbe.java "$WORK/flash"
grep -Fq 'MENGINE FLASH BUILDER P-CODE PASS' "$WORK/mengine.log" || { cat "$WORK/mengine.log" >&2; exit 1; }

run_session mengine-sentinel mengine.ap mengine-sentinel.log \
  -postScript RunMengineFlashBuilderProbe.java "$WORK/flash" sentinel
grep -Fq 'MENGINE FLASH BUILDER P-CODE PASS' "$WORK/mengine-sentinel.log" || { cat "$WORK/mengine-sentinel.log" >&2; exit 1; }

grep -h -E 'AFTER 4fe8|stored id|ROW |P-CODE PASS' "$WORK/config.log" "$WORK/mengine.log" "$WORK/mengine-sentinel.log" \
  | sed -E 's/ \(GhidraScript\)//; s/^INFO  [A-Za-z.]+> //'
