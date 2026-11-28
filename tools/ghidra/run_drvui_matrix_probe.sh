#!/usr/bin/env bash
# Run all drv_ui static checks in one Ghidra import.
# This is read-only analysis; it never modifies a firmware image.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/drv_ui.drv}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}
POINTERS=0x164c9001,0x164c90ed,0x26089471,0x260894e9,0x26409401

[ -f "$DRIVER" ] || { echo "missing driver: $DRIVER" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

# These are the audited mappings used by the individual drv_ui probes.  Keep
# them in one Ghidra project/import, while resetting the mapped memory between
# probe contexts so each assertion retains the original probe's boundaries.
WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-drvui-matrix.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project" "$WORK/home"

set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30drvuimatrix \
  -import "$DRIVER" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript FlatLoad.java "$DRIVER" 0x30000000 0 \
  -postScript FindPointerRefs.java "$POINTERS" \
  -postScript FindPointerClusters.java "$POINTERS" 0x40 \
  -postScript DumpAt.java \
    0x3000992c "$WORK/pointer.c" \
  -postScript FlatLoad.java "$DRIVER" 0x2609c980 0x8674 0x80c \
  -postScript DumpAt.java 0x260a5470,0x260a54e9 "$WORK/b2.c" \
  -postScript MapSegments.java 3 \
    "$DRIVER" 0x2611a980 0xa674 0x100c 1 \
    "$DRIVER" 0x261d7980 0xd674 0x100c 1 \
    "$DRIVER" 0x26216980 0xe674 0x100c 1 \
  -postScript DumpAt.java 0x26125750,0x261e5694,0x26225400 "$WORK/b3.c" \
  -postScript MapSegments.java 2 \
    "$DRIVER" 0x260db980 0x9674 0x80c 1 \
    "$DRIVER" 0x260db980 0x9e80 0x220 0 \
  -postScript DumpAt.java 0x260e5780 "$WORK/table.c" \
  -postScript DumpWords.java 0x260e59e8 0x260e5a14 \
  -postScript MapSegments.java 3 \
    "$DRIVER" 0x260db980 0x9674 0x80c 1 \
    "$DRIVER" 0xbfc08a00 0x1200 0x2fc 1 \
    "$DRIVER" 0xbfc0a600 0x200 0x1000 1 \
  -postScript DumpAt.java 0xbfc09ce0,0xbfc09c00,0xbfc09e34,0xbfc0a8bc,0xbfc0a8c8 "$WORK/resident.c" \
  -postScript DumpInstructions.java 0xbfc09c00 4 \
  -postScript FlatLoad.java "$DRIVER" 0x260db980 0x9674 0x80c \
  -postScript DumpAt.java 0x260e4ff4 "$WORK/runtime.c" \
  -postScript FlatLoad.java "$DRIVER" 0x260db980 0x9674 0x80c \
  -postScript MapSegmentsKeep.java 2 \
    "$DRIVER" 0xbfc08a00 0x1200 0x2fc 1 \
    "$DRIVER" 0x260db980 0x9e80 0x220 0 \
  -postScript DumpAt.java 0x260e5780,0xbfc09c00 "$WORK/import.c" \
  -postScript FindMips16Imports.java 0x260e4ff4 0x260e5800 15,17 \
  2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"

LOG="$WORK/ghidra.log"

# Pointer and bank-table invariants.
grep -q 'POINTER_TOTAL value=0x164c9001 count=4' "$LOG"
grep -q 'POINTER_TOTAL value=0x164c90ed count=5' "$LOG"
grep -q 'POINTER_TOTAL value=0x26089471 count=3' "$LOG"
grep -q 'POINTER_TOTAL value=0x260894e9 count=2' "$LOG"
grep -q 'POINTER_TOTAL value=0x26409401 count=2' "$LOG"
grep -q 'POINTER_CLUSTER_TOTAL hits=16' "$LOG"
grep -Eq 'POINTER_CLUSTER id=[0-9]+ start=0x992c end=0x996c hits=5' "$LOG"
grep -q 'WORD address=260e59e8 value=0xbfc09c05' "$LOG"
grep -q 'WORD address=260e59ec value=0xbfc09c01' "$LOG"
grep -q 'WORD address=260e59f0 value=0x26209401' "$LOG"
grep -q 'WORD address=260e59f4 value=0x261c9694' "$LOG"
grep -q 'WORD address=260e5a10 value=0x26109751' "$LOG"

# Every former separate decompile target must still resolve in its original
# mapping context.  The logs intentionally retain one marker per target.
for target in 260a5470 260a54e9 26125750 261e5694 26225400 \
  bfc09ce0 bfc09c00 bfc09e34 bfc0a8bc bfc0a8c8 260e4ff4; do
  grep -q "DumpAt: $target -> FUN_$target" "$LOG"
done
grep -q 'MIPS16_IMPORT_SUMMARY calls=5' "$LOG"
grep -q 'MIPS16_IMPORT_TOTAL selector=0xf count=1' "$LOG"
grep -q 'MIPS16_IMPORT_TOTAL selector=0x11 count=1' "$LOG"

# Decompiler contracts shared by the former B2, B3, resident, and runtime
# probes.  Keep these presentation-independent where possible.
grep -q 'return 2;' "$WORK/b2.c"
grep -q 'halt_baddata' "$WORK/b2.c"
grep -q 'void FUN_26125750' "$WORK/b3.c"
grep -q 'ushort FUN_261e5694' "$WORK/b3.c"
grep -q 'void FUN_26225400(void)' "$WORK/b3.c"
grep -q 'void FUN_bfc09e34' "$WORK/resident.c"
grep -q '_UNK_bfc09e70' "$WORK/resident.c"
grep -Fq '(*_UNK_bfc09e70)(param_1,param_2 & 0xffff,param_3);' "$WORK/resident.c"
grep -Fq '(*_UNK_bfc09e6c == '\''\x02'\'')' "$WORK/resident.c"
grep -q '_UNK_bfc0a8c4 + 0x10' "$WORK/resident.c"
grep -q '_UNK_bfc0a8d0 + 0x14' "$WORK/resident.c"
grep -Fq '(*_UNK_260e52bc)(_UNK_260e52b8,0,0x7c,1);' "$WORK/runtime.c"
grep -q 'uStack_40 = 0x7c;' "$WORK/runtime.c"
grep -Fq '(*_UNK_260e52e8)(&uStack_48,0,uVar7);' "$WORK/runtime.c"
grep -Fq '(*_UNK_260e52ec)(&uStack_48,&sStack_28,0);' "$WORK/runtime.c"

grep -q 'trap(0)' "$WORK/import.c"

grep -q 'INSN address=bfc09c04' "$LOG"
grep -q 'INSN address=bfc09c06.*break' "$LOG"
echo 'DRV_UI MATRIX PASS imports=1 checks=8'
