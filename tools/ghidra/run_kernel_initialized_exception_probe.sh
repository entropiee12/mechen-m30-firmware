#!/usr/bin/env bash
# Execute the extracted table constructor, then the actual kernel exception
# vector against that table. Stop at the fixed-ROM handler without executing it.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
KERNEL=${1:?usage: run_kernel_initialized_exception_probe.sh KERNEL.DRV KER_INIT.BIN}
INIT=${2:?usage: run_kernel_initialized_exception_probe.sh KERNEL.DRV KER_INIT.BIN}
KERNEL_SHA256=58f9d0c62bdbdab7cfeee1c5e65bbe8b88ee962f8eecd3dd0515d1a1056d8bd6
INIT_SHA256=f4c62848fb06ccbe6fba4323c4e29e46791c1abb25af648f0518eb0747492ec8
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}

[ -f "$KERNEL" ] || { echo "missing kernel.drv: $KERNEL" >&2; exit 1; }
[ -f "$INIT" ] || { echo "missing ker_init.bin: $INIT" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

kernel_sha=$(shasum -a 256 "$KERNEL" | awk '{print $1}')
init_sha=$(shasum -a 256 "$INIT" | awk '{print $1}')
[ "$kernel_sha" = "$KERNEL_SHA256" ] || {
  echo "kernel.drv SHA-256 $kernel_sha != $KERNEL_SHA256" >&2
  exit 1
}
[ "$init_sha" = "$INIT_SHA256" ] || {
  echo "ker_init.bin SHA-256 $init_sha != $INIT_SHA256" >&2
  exit 1
}
kernel_size=$(wc -c < "$KERNEL" | tr -d '[:space:]')
init_size=$(wc -c < "$INIT" | tr -d '[:space:]')
kernel_payload=$((kernel_size - 0x200))
[ "$kernel_payload" -gt 0 ] || { echo "kernel.drv is shorter than its header" >&2; exit 1; }
[ "$init_size" -gt 0 ] || { echo "ker_init.bin is empty" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-kernel-initialized-exc.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" kerinitexc \
    -import "$KERNEL" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript MapSegments.java 2 \
      "$KERNEL" 0xbfc07e00 0x200 "$kernel_payload" 0 \
      "$INIT" 0xbfc32000 0 "$init_size" 0 \
    -postScript RunKernelInitializedExceptionProbe.java "$KERNEL" "$INIT" \
    >"$WORK/run.log" 2>&1 || {
      cat "$WORK/run.log" >&2
      exit 1
    }

grep -Fq 'KERNEL INITIALIZED EXCEPTION PASS constructor=0xbfc32bc8 vector=0xbfc08180 cause=0x80000027 table-offset=0x24 handler=0xbfc06f18' "$WORK/run.log" || {
  cat "$WORK/run.log" >&2
  exit 1
}
grep 'KERNEL INITIALIZED EXCEPTION PASS' "$WORK/run.log"
grep 'KERNEL ROM HANDLER BOUNDARY' "$WORK/run.log"
