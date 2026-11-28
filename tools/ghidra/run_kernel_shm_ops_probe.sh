#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
KERNEL=${1:-$ROOT/analysis/community/fw/kernel.drv}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
EXPECTED=58f9d0c62bdbdab7cfeee1c5e65bbe8b88ee962f8eecd3dd0515d1a1056d8bd6
ACTUAL=$(shasum -a 256 "$KERNEL" | awk '{print $1}')

if [ "$ACTUAL" != "$EXPECTED" ]; then
  echo "SHA-256 mismatch for $KERNEL: $ACTUAL" >&2
  exit 1
fi

if [ ! -x "$GHIDRA" ]; then
  echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2
  exit 1
fi

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-kernel-shm.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

if ! GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" kernelshm \
    -import "$KERNEL" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript MapSegments.java 3 \
      "$KERNEL" 0xbfc07e00 0x200 0xb00 1 \
      "$KERNEL" 0x7600 0x1a00 0x300 1 \
      "$KERNEL" 0x9fc19700 0xd00 0x8c 0 \
    -postScript RunKernelShmOps.java \
    >"$WORK/run.log" 2>&1; then
  cat "$WORK/run.log" >&2
  exit 1
fi

if ! grep -Fq 'KERNEL SHM OPS P-CODE PASS' "$WORK/run.log"; then
  cat "$WORK/run.log" >&2
  exit 1
fi

grep -E 'lookup-table|attach\(|create\(|delete\(|KERNEL SHM OPS' "$WORK/run.log"
