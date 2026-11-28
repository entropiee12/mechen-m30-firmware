#!/usr/bin/env bash
# Decompile the extracted USB Audio device boundary. Static analysis only.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/usound.drv}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}

[ -f "$DRIVER" ] || { echo "missing USB Audio driver: $DRIVER" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

PYTHONPATH="$ROOT/tools/emu" python3 "$ROOT/tools/emu/usound_binary_probe.py" "$DRIVER"

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-usound.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project"

# file 0x200 is loaded at 0xbfc30000, so file byte zero is bfc2fe00.
set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30usound \
  -import "$DRIVER" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript FlatLoad.java "$DRIVER" 0xbfc2fe00 0x200 0x26b0 \
  -postScript DumpC.java "$WORK/usound.c" 2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"

grep -q 'FlatLoad: bytes=9904 functions=46' "$WORK/ghidra.log"
for address in bfc302f8 bfc3036c bfc303c4; do
  grep -q "FUN_$address" "$WORK/usound.c"
done
grep -Fq '*_UNK_bfc30354 = 200;' "$WORK/usound.c"
grep -Fq '*_UNK_bfc3035c = 0x84;' "$WORK/usound.c"
grep -Fq 'if (uStack_c == 0x22)' "$WORK/usound.c"
grep -Fq 'if (uStack_c == 0x81)' "$WORK/usound.c"
grep -Fq '_UNK_bfc30c38[1] == 0x82' "$WORK/usound.c"
grep -q 'uint FUN_bfc31654' "$WORK/usound.c"
grep -Fq '0x1000 - *_UNK_bfc31730' "$WORK/usound.c"
grep -Fq 'if (0xfff < *_UNK_bfc31730)' "$WORK/usound.c"
grep -Fq 'if (99 < *_UNK_bfc3172c)' "$WORK/usound.c"
grep -q 'void FUN_bfc30cac' "$WORK/usound.c"
grep -q 'void FUN_bfc30ce8' "$WORK/usound.c"
grep -Fq 'iStackX_8 = iStackX_8 + -3' "$WORK/usound.c"
grep -Fq '0x6e40' "$WORK/usound.c"
grep -Fq 'iStack_3c = 0x1b90' "$WORK/usound.c"
grep -Fq 'iStack_3c = 0x3720' "$WORK/usound.c"
grep -q 'void FUN_bfc30dc4' "$WORK/usound.c"
grep -q 'void FUN_bfc31114' "$WORK/usound.c"
echo 'USB AUDIO DRIVER PASS functions=46 control-requests=0x22/0x21 hid-endpoint=0x82 iso-packet=200 ring-boundary=0x1000 software-ring=0x6e40 format=3-to-4'
