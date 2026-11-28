#!/usr/bin/env bash
# Run the read-only QEMU CPU/RAM boundary probes for the community corpus.
# These probes execute recovered MIPS code with explicit synthetic shims; they
# do not boot an ATJ2127 machine, modify firmware, or write an SD card.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
if [ "${1:-}" = "--all-corpora" ]; then
  COUNT=0
  for FW in "$ROOT"/analysis/*/fw; do
    [ -f "$FW/drv_lcd.drv" ] && [ -f "$FW/drv_dac.drv" ] \
      && [ -f "$FW/usound.drv" ] || continue
    COUNT=$((COUNT + 1))
    echo "=== QEMU boundary corpus: ${FW#"$ROOT/analysis/"} ==="
    bash "$ROOT/tools/emu/run_qemu_boundary_suite.sh" "$FW"
  done
  [ "$COUNT" -gt 0 ] || { echo "no complete firmware corpora found" >&2; exit 1; }
  echo "QEMU BOUNDARY CORPUS MATRIX PASS corpora=$COUNT"
  exit 0
fi

FW=${1:-$ROOT/analysis/community/fw}

[ -d "$FW" ] || { echo "missing firmware directory: $FW" >&2; exit 1; }

echo '[1/9] LCD resident boundary'
bash "$ROOT/tools/emu/run_qemu_lcd_probe.sh" "$FW/drv_lcd.drv"

echo '[2/9] DAC byte-transfer boundary'
bash "$ROOT/tools/emu/run_qemu_dac_probe.sh" "$FW/drv_dac.drv"

echo '[3/9] USB Audio packet/ring boundary'
bash "$ROOT/tools/emu/run_qemu_usb_audio_probe.sh"

echo '[4/9] usound leaf and producer boundaries'
bash "$ROOT/tools/emu/run_qemu_usound_leaf_probe.sh" "$FW/usound.drv"
bash "$ROOT/tools/emu/run_qemu_usound_producer_probe.sh" "$FW/usound.drv"

echo '[5/9] usound ring and port boundaries'
bash "$ROOT/tools/emu/run_qemu_usound_ring_probe.sh" "$FW/usound.drv"
bash "$ROOT/tools/emu/run_qemu_usound_port_probe.sh" "$FW/usound.drv"

echo '[6/9] usound producer-consumer chain'
bash "$ROOT/tools/emu/run_qemu_usound_chain_probe.sh" "$FW/usound.drv"

echo '[7/9] usound event-DMA plus synthetic composition probe'
USOUND_SHA256=$(shasum -a 256 "$FW/usound.drv" | awk '{print $1}')
if [ "$USOUND_SHA256" = "e593bdd29c2a96d4d745cf1b5cae4da2ee71c2211c551e415209b18f54e600fc" ]; then
  bash "$ROOT/tools/emu/run_qemu_usound_event_dma_probe.sh" "$FW/usound.drv"
  bash "$ROOT/tools/emu/run_qemu_usound_composition_probe.sh" "$FW/usound.drv"
else
  echo "SKIP hash-pinned usound probes: unsupported SHA-256 $USOUND_SHA256"
fi

echo '[8/9] break-0 module-call envelope'
bash "$ROOT/tools/emu/run_qemu_usound_callback_probe.sh" "$FW/usound.drv"

echo '[9/9] independent TCG exception observation'
bash "$ROOT/tools/emu/run_qemu_exception_trace_probe.sh" "$FW/usound.drv"

echo 'QEMU BOUNDARY SUITE PASS'
