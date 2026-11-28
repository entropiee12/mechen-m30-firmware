#!/usr/bin/env bash
# Execute the extracted kernel-init CP0/interrupt setup with explicit CP0 semantics.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
INIT=${1:?usage: run_kernel_init_cp0_probe.sh KER_INIT.BIN}
INIT_SHA256=f4c62848fb06ccbe6fba4323c4e29e46791c1abb25af648f0518eb0747492ec8
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}

[ -f "$INIT" ] || { echo "missing ker_init.bin: $INIT" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
init_sha=$(shasum -a 256 "$INIT" | awk '{print $1}')
[ "$init_sha" = "$INIT_SHA256" ] || {
  echo "ker_init.bin SHA-256 $init_sha != $INIT_SHA256" >&2
  exit 1
}
init_size=$(wc -c < "$INIT" | tr -d '[:space:]')
[ "$init_size" -gt $((0x440)) ] || { echo "ker_init.bin truncated before helper" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-kernel-init-cp0.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" kerncp0 \
    -import "$INIT" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript FlatLoad32.java "$INIT" 0xbfc32000 0 "$init_size" \
    -postScript RunKernelInitCp0Probe.java "$INIT" \
    >"$WORK/run.log" 2>&1 || {
      cat "$WORK/run.log" >&2
      exit 1
    }

grep -Fq 'KERNEL INIT ENTRY PASS entry=0xbfc32000 helper=0xbfc32224 first-rom-edge=0xbfc045f5 stop=0xbfc045f5' "$WORK/run.log" || {
  cat "$WORK/run.log" >&2
  exit 1
}
grep 'KERNEL INIT ENTRY PASS' "$WORK/run.log"
