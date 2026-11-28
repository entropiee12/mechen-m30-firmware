#!/usr/bin/env bash
# Compare the stock and community bytes at the two automatic-index guard sites.
# The decompiler's in_v0 labels are not treated as the semantic condition.
# No firmware image is modified or produced.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
STOCK=${1:-$ROOT/analysis/stock/fw}
COMMUNITY=${2:-$ROOT/analysis/community/fw}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}

[ -f "$STOCK/config.ap" ] || { echo "missing stock config.ap" >&2; exit 1; }
[ -f "$STOCK/udisk.ap" ] || { echo "missing stock udisk.ap" >&2; exit 1; }
[ -f "$COMMUNITY/config.ap" ] || { echo "missing community config.ap" >&2; exit 1; }
[ -f "$COMMUNITY/udisk.ap" ] || { echo "missing community udisk.ap" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-index-guards.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

decompile() {
  local label=$1
  local root=$2
  local app=$3
  local address=$4
  mkdir -p "$WORK/$label-project"
  set +e
  JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
  GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home-$label}" \
    "$GHIDRA" "$WORK/$label-project" "m30$label" \
    -import "$root/$app.ap" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$root/$app.ap" \
    -postScript DumpAt.java "$address" "$WORK/$label.c" \
    2>&1 | tee "$WORK/$label.log"
  local rc=${PIPESTATUS[0]}
  set -e
  [ "$rc" -eq 0 ] || exit "$rc"
}

decompile stock-config "$STOCK" config 0x40094e0c
decompile community-config "$COMMUNITY" config 0x40094e0c
decompile stock-udisk "$STOCK" udisk 0x40055008
decompile community-udisk "$COMMUNITY" udisk 0x40055008

for label in stock-config community-config stock-udisk community-udisk; do
  grep -q 'DumpAt:' "$WORK/$label.log"
done

# Compare the full two-byte MIPS16 instructions. The old decompiler summary
# calling these incoming-v0 conditions was not a reliable semantic reading.
[ "$(od -An -tx1 -j 0x1e0c -N 2 "$COMMUNITY/config.ap" | tr -d ' \n')" = "0d10" ]
[ "$(od -An -tx1 -j 0x1908 -N 2 "$COMMUNITY/udisk.ap" | tr -d ' \n')" = "0810" ]
[ "$(od -An -tx1 -j 0x1e0c -N 2 "$STOCK/config.ap" | tr -d ' \n')" = "0d22" ]
[ "$(od -An -tx1 -j 0x1908 -N 2 "$STOCK/udisk.ap" | tr -d ' \n')" = "0822" ]

echo 'INDEX GUARD BYTE PROBE PASS config=0d22-to-0d10 udisk=0822-to-0810'
