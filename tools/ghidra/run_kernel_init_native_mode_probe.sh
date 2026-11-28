#!/usr/bin/env bash
# Exercise native MIPS ISA-mode transitions around extracted kernel helper calls.
set -euo pipefail

if [ "$#" -gt 5 ]; then
  echo "usage: $0 [KER_INIT.BIN] [forward|reverse|seeded] [clear-zero|clear-no-effect] [return-zero|return-dst|return-sentinel] [callback-stop|callback-return]" >&2
  exit 2
fi

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
INIT=${1:-$ROOT/analysis/community/fw/ker_init.bin}
MODE=${2:-forward}
CLEAR_MODE=${3:-clear-zero}
CLEAR_RETURN_MODE=${4:-return-zero}
CALLBACK_MODE=${5:-callback-stop}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
EXPECTED_SHA256=f4c62848fb06ccbe6fba4323c4e29e46791c1abb25af648f0518eb0747492ec8

[ -f "$INIT" ] || { echo "missing ker_init.bin: $INIT" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
actual_sha=$(shasum -a 256 "$INIT" | awk '{print $1}')
[ "$actual_sha" = "$EXPECTED_SHA256" ] || {
  echo "ker_init.bin SHA-256 $actual_sha != $EXPECTED_SHA256" >&2
  exit 1
}
init_size=$(wc -c < "$INIT" | tr -d '[:space:]')
[ "$init_size" -gt $((0x800)) ] || { echo "ker_init.bin truncated before helper" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-native-mode.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" kernnative \
    -import "$INIT" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript FlatLoad32.java "$INIT" 0xbfc32000 0 "$init_size" \
    -postScript RunKernelInitNativeModeProbe.java "$INIT" "$MODE" "$CLEAR_MODE" "$CLEAR_RETURN_MODE" "$CALLBACK_MODE" \
    >"$WORK/run.log" 2>&1 || {
      cat "$WORK/run.log" >&2
      exit 1
    }

if [ "$MODE" = reverse ]; then
  EXPECTED='KERNEL TEMPLATE COPY CONTROL PASS direction=reverse callback-slot=0 indirect-target=0'
else
  EXPECTED="KERNEL TEMPLATE ISA MODE PASS mode=$MODE entry=bfc322f8 callback=9fc27f00 rom-entry-isa-mode=1 relp=1 callback-isa-mode=0 relp=1 synthetic-mips16-return-stubs=2 args=fffffff0,bfc0855d,00000000,04040404"
fi
grep -Fq "$EXPECTED" "$WORK/run.log" || {
  cat "$WORK/run.log" >&2
  exit 1
}
grep -F "$EXPECTED" "$WORK/run.log"
grep -Fq "SYNTHETIC ROM CLEAR STATE PASS mode=$CLEAR_MODE return=$CLEAR_RETURN_MODE" "$WORK/run.log" || {
  cat "$WORK/run.log" >&2
  exit 1
}
grep -F "SYNTHETIC ROM CLEAR STATE PASS mode=$CLEAR_MODE return=$CLEAR_RETURN_MODE" "$WORK/run.log"
if [ "$CALLBACK_MODE" = callback-return ]; then
  grep -Fq "KERNEL TEMPLATE POST-CALLBACK CLEAR PASS mode=$CLEAR_MODE return=$CLEAR_RETURN_MODE" "$WORK/run.log" || {
    cat "$WORK/run.log" >&2
    exit 1
  }
  grep -F "KERNEL TEMPLATE POST-CALLBACK CLEAR PASS mode=$CLEAR_MODE return=$CLEAR_RETURN_MODE" "$WORK/run.log"
fi
