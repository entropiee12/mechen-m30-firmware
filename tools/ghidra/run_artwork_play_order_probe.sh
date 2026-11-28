#!/usr/bin/env bash
# Trace the real music.ap branch that can call artwork refresh before PLAY.
# Resident UI/engine boundaries are stubbed; this is not a device timing test.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:-$ROOT/analysis/community/fw/music.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}}
PYTHON=${PYTHON:-python3}
CROSS=${CROSS:-mipsel-linux-gnu-}
WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-artwork-play-order.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

[ -f "$AP" ] || { echo "missing music.ap: $AP" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
for tool in "${CROSS}as" "${CROSS}objcopy"; do
  command -v "$tool" >/dev/null || { echo "missing $tool" >&2; exit 1; }
done

mkdir -p "$WORK/probe" "$WORK/project" "$WORK/home"
PYTHONPATH="$ROOT/tools/emu" "$PYTHON" "$ROOT/tools/emu/make_qemu_probe.py" "$AP" "$WORK/probe"
(
  cd "$WORK/probe"
  "${CROSS}as" -EL -mabi=32 -mips32 -o qemu_probe.o qemu_probe.S
  "${CROSS}objcopy" -O binary -j .stubs qemu_probe.o stubs.bin
)

JAVA_HOME="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30artworkplayorder \
  -import "$AP" -loader BinaryLoader -processor 'MIPS:LE:32:default' -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript LoadAP.java "$AP" \
  -postScript RunArtworkPlayOrderProbe.java "$WORK/probe/stubs.bin" \
  >"$WORK/run.log" 2>&1

if grep -q 'ERROR REPORT SCRIPT ERROR' "$WORK/run.log"; then
  cat "$WORK/run.log" >&2
  echo 'probe script error; result is not a pass' >&2
  exit 1
fi
grep 'ARTWORK PLAY ORDER DYNAMIC PASS' "$WORK/run.log"
