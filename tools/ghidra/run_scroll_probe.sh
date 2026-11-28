#!/usr/bin/env bash
# Run the real MIPS16 now-playing scroll path in Ghidra's p-code emulator.
# This is an isolated probe: firmware banks are not modified and no HEX is
# produced.
set -euo pipefail

[ $# -ge 1 ] || { echo "usage: $0 MUSIC.AP [STUBS.BIN|cancel|next|previous|redraw|render-next|render-previous|render-redraw] [cancel|next|previous|redraw|render-next|render-previous|render-redraw]" >&2; exit 2; }
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
STUBS=
MODE=cancel
if [ $# -ge 2 ]; then
  case "$2" in
    cancel|next|previous|redraw|render-next|render-previous|render-redraw) MODE=$2 ;;
    *) STUBS=$2 ;;
  esac
fi
if [ $# -ge 3 ]; then MODE=$3; fi
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}
TMP_ROOT=${TMPDIR:-/private/tmp}
WORK=$(mktemp -d "$TMP_ROOT/m30-ghidra-scroll.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

if [ -z "$STUBS" ]; then
  PROBE_OUT="$WORK/probe"
  mkdir -p "$PROBE_OUT"
  CROSS=${CROSS:-mipsel-linux-gnu-}
  PY=${PYTHON:-python3}
  command -v "$PY" >/dev/null || { echo "missing Python: $PY" >&2; exit 1; }
  for tool in "${CROSS}as" "${CROSS}ld" "${CROSS}objcopy"; do
    command -v "$tool" >/dev/null || { echo "missing MIPS binutils: $tool" >&2; exit 1; }
  done
  PYTHONPATH="$ROOT/tools/emu" "$PY" "$ROOT/tools/emu/make_qemu_probe.py" "$AP" "$PROBE_OUT"
  (
    cd "$PROBE_OUT"
    "${CROSS}as" -EL -mabi=32 -mips32 -o qemu_probe.o qemu_probe.S
    "${CROSS}objcopy" -O binary -j .stubs qemu_probe.o stubs.bin
  )
  STUBS="$PROBE_OUT/stubs.bin"
fi

[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
[ -f "$STUBS" ] || { echo "missing stubs binary: $STUBS" >&2; exit 1; }

mkdir -p "$WORK/project"
LOG="$WORK/ghidra.log"
set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30scroll \
  -import "$AP" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript LoadAP.java "$AP" \
  -postScript RunScrollProbe.java "$STUBS" "$MODE" 2>&1 | tee "$LOG"
GHIDRA_STATUS=${PIPESTATUS[0]}
set -e
if [ "$GHIDRA_STATUS" -ne 0 ]; then
  exit "$GHIDRA_STATUS"
fi
if ! grep -Eq 'SCROLL (PROBE|EVENT|TIMER ABI) PASS' "$LOG"; then
  echo "Ghidra completed without a probe PASS marker" >&2
  exit 1
fi
