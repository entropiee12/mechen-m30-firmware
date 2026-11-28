#!/usr/bin/env bash
# Execute Custom-row registration through its real deferred-callback dispatcher.
# Optional second argument captures 4 message bytes + 7 engine Custom bytes.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
MUSIC=${1:-$ROOT/analysis/community/fw/music.ap}
CAPTURE=${2:-}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
STOCK_SHA256=faa6b68a68de78ef45c2a9d9ad664b6b6310a93f21f9a07b29467827abd49193
COMMUNITY_SHA256=662e379602707a9c4b0dcd6b94b591dd77c2b5f7a557fcb03b8c2a5c9b1baac3

[ -f "$MUSIC" ] || { echo "missing music.ap: $MUSIC" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
actual_sha256=$(shasum -a 256 "$MUSIC" | awk '{print $1}')
case "$actual_sha256" in
  "$STOCK_SHA256") variant=stock-v1.101.10 ;;
  "$COMMUNITY_SHA256") variant=community-v1.292.00 ;;
  *) echo "unsupported music.ap SHA-256: $actual_sha256" >&2; exit 1 ;;
esac

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-eq-custom-framework.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

if [ -n "$CAPTURE" ]; then
  SCRIPT_ARGS=(-postScript RunEqCustomFrameworkCallbackProbe.java "$CAPTURE")
else
  SCRIPT_ARGS=(-postScript RunEqCustomFrameworkCallbackProbe.java)
fi

GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30eqcustomframework \
    -import "$MUSIC" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$MUSIC" \
    "${SCRIPT_ARGS[@]}" \
    >"$WORK/run.log" 2>&1 || {
      cat "$WORK/run.log" >&2
      exit 1
    }

if ! grep -Fq 'EQ CUSTOM FRAMEWORK CALLBACK PASS ' "$WORK/run.log"; then
  cat "$WORK/run.log" >&2
  echo "Ghidra completed without the expected framework-dispatched callback marker" >&2
  exit 1
fi
grep -F 'EQ CUSTOM FRAMEWORK CALLBACK PASS ' "$WORK/run.log"
echo "EQ CUSTOM FRAMEWORK CORPUS PASS variant=$variant"
