#!/usr/bin/env bash
# Map drv_ui's B3 and resident code segments together and verify the
# resident targets used by the B3 literal pool.
# Static Ghidra analysis only; no firmware image is modified.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/drv_ui.drv}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}

[ -f "$DRIVER" ] || { echo "missing driver: $DRIVER" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

# B3 includes the marquee lead-in. The two resident segments are the exact
# yqhx payload ranges from drv_ui.drv: file 0x1200 -> 0xbfc09c00 and file
# 0x200 -> 0xbfc0a800. All three regions are MIPS16.
WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-drvui-resident.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project"

set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30resident \
  -import "$DRIVER" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript MapSegments.java 3 \
    "$DRIVER" 0x260db980 0x9674 0x80c 1 \
    "$DRIVER" 0xbfc08a00 0x1200 0x2fc 1 \
    "$DRIVER" 0xbfc0a600 0x200 0x1000 1 \
  -postScript DumpAt.java \
    0xbfc09ce0,0xbfc09c00,0xbfc09e34,0xbfc0a8bc,0xbfc0a8c8 "$WORK/decomp.c" \
  -postScript DumpInstructions.java 0xbfc09c00 4 \
  2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"

for target in bfc09ce0 bfc09c00 bfc09e34 bfc0a8bc bfc0a8c8; do
  grep -q "DumpAt: $target -> FUN_$target" "$WORK/ghidra.log"
done
grep -q 'trap(0)' "$WORK/decomp.c"
grep -q 'void FUN_bfc09e34' "$WORK/decomp.c"
grep -q '_UNK_bfc09e70' "$WORK/decomp.c"
grep -Fq '(*_UNK_bfc09e70)(param_1,param_2 & 0xffff,param_3);' "$WORK/decomp.c"
grep -Fq '(*_UNK_bfc09e6c == '\''\x02'\'')' "$WORK/decomp.c"
grep -q '_UNK_bfc0a8c4 + 0x10' "$WORK/decomp.c"
grep -q '_UNK_bfc0a8d0 + 0x14' "$WORK/decomp.c"
grep -q 'INSN address=bfc09c04' "$WORK/ghidra.log"
grep -q 'INSN address=bfc09c06.*break' "$WORK/ghidra.log"
echo 'DRV_UI RESIDENT PASS syscall=bfc09c00 mode_forwarder=bfc09e34 state_setters=bfc0a8bc,bfc0a8c8'
