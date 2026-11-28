#!/usr/bin/env bash
# Execute real EQ-builder output through the candidate aud_dev band consumer.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
MENGINE_COMMUNITY=${1:-$ROOT/analysis/community/fw/mengine.ap}
MENGINE_STOCK=${2:-$ROOT/analysis/stock/fw/mengine.ap}
AUDDEV=${3:-$ROOT/analysis/community/fw/aud_dev.drv}
OUT=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-eq-auddev-chain.XXXXXX")
trap 'rm -rf "$OUT"' EXIT

bash "$ROOT/tools/ghidra/run_eq_builder_probe.sh" \
  "$MENGINE_COMMUNITY" "$MENGINE_STOCK" "$OUT/structures" >"$OUT/builder.log"
grep 'EQ BUILDER CORPUS PASS' "$OUT/builder.log"

python3 - "$OUT/structures" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
for name in ("off", "hip-hop", "custom-default", "custom-candidate"):
    source = (root / f"{name}.bin").read_bytes()
    if len(source) != 0xF0:
        raise SystemExit(f"{name}: expected 0xf0-byte builder output")
    if source[0xC0:0xCC] != bytes([0xAA]) * 12:
        raise SystemExit(f"{name}: expected preserved 0xaa adjustment sentinel")
    neutral = bytearray(source)
    neutral[0xC0:0xCC] = bytes(12)
    if neutral[0x10:0x90] != source[0x10:0x90]:
        raise SystemExit(f"{name}: neutralization changed EQ bands")
    (root / f"{name}-neutral.bin").write_bytes(neutral)

    ranges = bytearray(source)
    ranges[0xC0:0xD8] = bytes(0x18)
    ranges[0xC4:0xC6] = (1000).to_bytes(2, "little")
    ranges[0xC6:0xC8] = (25).to_bytes(2, "little", signed=True)
    ranges[0xC8:0xCA] = (8000).to_bytes(2, "little")
    ranges[0xCA:0xCC] = (-15).to_bytes(2, "little", signed=True)
    if ranges[0x10:0x90] != source[0x10:0x90]:
        raise SystemExit(f"{name}: range-parameter injection changed EQ bands")
    (root / f"{name}-ranges.bin").write_bytes(ranges)
PY

for preset in off hip-hop custom-default custom-candidate; do
  for variant in preserved-tail neutral-adjustments two-range-adjustments; do
    suffix=""
    expectation=decoded-adjustments
    if [ "$variant" = "neutral-adjustments" ]; then
      suffix="-neutral"
      expectation=neutral
    elif [ "$variant" = "two-range-adjustments" ]; then
      suffix="-ranges"
    fi
    input="$OUT/structures/$preset$suffix.bin"
    log="$OUT/$preset-$variant.log"
    bash "$ROOT/tools/ghidra/run_auddev_eq_copy_probe.sh" \
      "$AUDDEV" "$input" "$expectation" >"$log"
    grep 'AUDDEV EQ COPY P-CODE PASS' "$log"
  done
done

python3 - "$OUT/structures" <<'PY'
from hashlib import sha256
from pathlib import Path
import sys

root = Path(sys.argv[1])
for name in ("off", "hip-hop", "custom-default", "custom-candidate"):
    data = (root / f"{name}.bin").read_bytes()
    if len(data) != 0xF0:
        raise SystemExit(f"{name}: expected 0xf0 bytes, got 0x{len(data):x}")
    print(f"EQ STRUCTURE preset={name} sha256={sha256(data).hexdigest()}")
PY

echo "EQ PRODUCER->AUDDEV CANDIDATE PASS presets=off-hip-hop-custom-default-custom-candidate variants=preserved-tail-neutral-two-range service=synthetic-memcpy dispatcher=unverified dsp-output=unverified"
