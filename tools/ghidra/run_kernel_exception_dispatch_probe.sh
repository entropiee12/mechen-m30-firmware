#!/usr/bin/env bash
# Execute the real MIPS32 exception vector with synthetic exception-table entries.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
KERNEL=${1:?usage: run_kernel_exception_dispatch_probe.sh KERNEL.DRV}
EXPECTED_SHA256=58f9d0c62bdbdab7cfeee1c5e65bbe8b88ee962f8eecd3dd0515d1a1056d8bd6
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}

[ -f "$KERNEL" ] || { echo "missing kernel.drv: $KERNEL" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
actual_sha256=$(shasum -a 256 "$KERNEL" | awk '{print $1}')
[ "$actual_sha256" = "$EXPECTED_SHA256" ] || {
  echo "kernel.drv SHA-256 $actual_sha256 != $EXPECTED_SHA256" >&2
  exit 1
}
kernel_size=$(wc -c < "$KERNEL")
segment_size=$((kernel_size - 0x200))
[ "$segment_size" -gt 0 ] || { echo "kernel.drv is shorter than its 0x200-byte header" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-kernel-dispatch.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" kerdispatch \
    -import "$KERNEL" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript MapSegments.java 1 "$KERNEL" 0xbfc07e00 0x200 "$segment_size" 0 \
    -postScript RunKernelExceptionDispatchProbe.java "$KERNEL" \
    >"$WORK/run.log" 2>&1 || {
      cat "$WORK/run.log" >&2
      exit 1
    }

grep -Fq 'KERNEL EXCEPTION DISPATCH P-CODE PASS vector=0xbfc08180 exc9-noisy=0xbfc06f18 exc8-noisy=0x00700100 synthetic-table=1 rom-handler-executed=0' "$WORK/run.log" || {
  cat "$WORK/run.log" >&2
  exit 1
}
grep 'KERNEL EXCEPTION case=' "$WORK/run.log"
grep 'KERNEL EXCEPTION DISPATCH P-CODE PASS' "$WORK/run.log"
