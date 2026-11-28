#!/usr/bin/env bash
# Inspect the tiny audio-device member. Static analysis only.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/aud_dev.drv}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}

[ -f "$DRIVER" ] || { echo "missing audio-device driver: $DRIVER" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

PYTHONPATH="$ROOT/tools/emu" python3 "$ROOT/tools/emu/auddev_binary_probe.py" "$DRIVER"

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-auddev.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project"

# The eight-byte MIPS32 payload at file 0x300 is loaded at 0xbfc09f58.
set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30auddev \
  -import "$DRIVER" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript MapSegments.java 1 "$DRIVER" 0xbfc09c58 0x300 0x8 0 \
  -postScript DumpAt.java 0xbfc09f58 "$WORK/auddev.c" 2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"

grep -q 'MapSegments: .* -> 0xbfc09f58 size=0x8 isa=0' "$WORK/ghidra.log"
grep -q 'DumpAt: bfc09f58 -> FUN_bfc09f58' "$WORK/ghidra.log"
grep -q 'getCopReg(2,5)' "$WORK/auddev.c"
grep -q '0x6b10' "$WORK/auddev.c"
grep -q '0x6b0f' "$WORK/auddev.c"
echo 'AUDIO DEVICE DRIVER PASS bytes=8 complete-path=no cop2-writes=2'
