#!/usr/bin/env bash
# Change one synthetic fixed-ROM return at a time and rerun the checked caller.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
if [ "$#" -gt 1 ]; then
  echo "usage: $0 [KER_INIT.BIN]" >&2
  exit 2
fi
INIT=${1:-$ROOT/analysis/community/fw/ker_init.bin}
EXPECTED_BASELINE_TRACE=079eb760cddc1e581c7c4aba54dbff55495e387cc0471577e41cf0e6ffed8eae
LOG=$(mktemp "${TMPDIR:-/private/tmp}/m30-kernel-mmio-return-sweep.XXXXXX")
trap 'rm -f "$LOG"' EXIT

if ! bash "$ROOT/tools/ghidra/run_kernel_init_mmio_probe.sh" \
    "$INIT" zero >"$LOG" 2>&1; then
  cat "$LOG" >&2
  exit 1
fi
baseline_trace=$(sed -E -n 's/^.*KERNEL INIT MMIO TRACE SHA256=([0-9a-f]{64}).*$/\1/p' "$LOG")
[ -n "$baseline_trace" ] || { cat "$LOG" >&2; exit 1; }
if [ "$baseline_trace" != "$EXPECTED_BASELINE_TRACE" ]; then
  echo "zero-return trace changed: $baseline_trace != $EXPECTED_BASELINE_TRACE" >&2
  exit 1
fi
grep -F "KERNEL INIT MMIO P-CODE PASS entry=bfc32440" "$LOG" \
  | grep -F 'return-mode=zero'
echo "KERNEL INIT MMIO BASELINE TRACE SHA256=$baseline_trace"

for call_index in 0 1 2 3 4 5 6; do
  for return_value in sentinel one minus1; do
    if [ "$return_value" = sentinel ]; then
      mode="call${call_index}"
    else
      mode="call${call_index}-${return_value}"
    fi
    if ! bash "$ROOT/tools/ghidra/run_kernel_init_mmio_probe.sh" \
        "$INIT" "$mode" >"$LOG" 2>&1; then
      cat "$LOG" >&2
      exit 1
    fi
    grep -F "KERNEL INIT MMIO P-CODE PASS entry=bfc32440" "$LOG" \
      | grep -F "return-mode=$mode"
    trace=$(sed -E -n 's/^.*KERNEL INIT MMIO TRACE SHA256=([0-9a-f]{64}).*$/\1/p' "$LOG")
    if [ "$trace" != "$baseline_trace" ]; then
      echo "instruction trace differs for $mode: $trace != $baseline_trace" >&2
      exit 1
    fi
    echo "KERNEL INIT MMIO TRACE MATCH profile=$mode sha256=$trace"
  done
done

for mode in one minus1; do
  if ! bash "$ROOT/tools/ghidra/run_kernel_init_mmio_probe.sh" \
      "$INIT" "$mode" >"$LOG" 2>&1; then
    cat "$LOG" >&2
    exit 1
  fi
  grep -F "KERNEL INIT MMIO P-CODE PASS entry=bfc32440" "$LOG" \
    | grep -F "return-mode=$mode"
  trace=$(sed -E -n 's/^.*KERNEL INIT MMIO TRACE SHA256=([0-9a-f]{64}).*$/\1/p' "$LOG")
  if [ "$trace" != "$baseline_trace" ]; then
    echo "instruction trace differs for $mode: $trace != $baseline_trace" >&2
    exit 1
  fi
  echo "KERNEL INIT MMIO TRACE MATCH profile=$mode sha256=$trace"
done

echo "KERNEL INIT MMIO RETURN SWEEP PASS isolated-call-profiles=21 global-return-profiles=3 trace=identical"
