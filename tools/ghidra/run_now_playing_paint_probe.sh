#!/usr/bin/env bash
# Run the real music.ap now-playing paint entry in Ghidra's p-code emulator.
# This is trace-only: the AP is copied into a temporary analysis project and
# only two literal-pool pointers are redirected in emulator memory.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:-$ROOT/analysis/community/fw/music.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}
PYTHON=${PYTHON:-python3}
CROSS=${CROSS:-mipsel-linux-gnu-}
WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-now-playing.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

[ -f "$AP" ] || { echo "missing music.ap: $AP" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
command -v "$PYTHON" >/dev/null || { echo "missing Python: $PYTHON" >&2; exit 1; }
for tool in "${CROSS}as" "${CROSS}ld" "${CROSS}objcopy"; do
  command -v "$tool" >/dev/null || { echo "missing MIPS binutils: $tool" >&2; exit 1; }
done

PROBE_OUT="$WORK/probe"
mkdir -p "$PROBE_OUT/project"
PYTHONPATH="$ROOT/tools/emu" "$PYTHON" "$ROOT/tools/emu/make_qemu_probe.py" "$AP" "$PROBE_OUT"
(
  cd "$PROBE_OUT"
  "${CROSS}as" -EL -mabi=32 -mips32 -o qemu_probe.o qemu_probe.S
  "${CROSS}objcopy" -O binary -j .stubs qemu_probe.o stubs.bin
)

LOG="$WORK/ghidra.log"
set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$PROBE_OUT/project" m30nowplaying \
  -import "$AP" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript LoadAP.java "$AP" \
  -postScript RunNowPlayingPaintProbe.java "$PROBE_OUT/stubs.bin" 2>&1 | tee "$LOG"
STATUS=${PIPESTATUS[0]}
set -e
if [ "$STATUS" -ne 0 ]; then exit "$STATUS"; fi
if grep -q 'ERROR REPORT SCRIPT ERROR' "$LOG"; then
  echo "Ghidra script reported an error; probe is not considered a pass" >&2
  exit 1
fi
grep -q 'NOW PLAYING PAINT DYNAMIC PASS' "$LOG"
