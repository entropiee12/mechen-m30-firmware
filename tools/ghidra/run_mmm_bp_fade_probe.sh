#!/usr/bin/env bash
# Map mmm_bp.al and verify the fade/buffer diagnostic boundary.
# Static Ghidra analysis only; no decoder or DSP payload is executed.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AL=${1:-$ROOT/analysis/community/fw/mmm_bp.al}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}

if [ -z "${JAVA_HOME:-}" ] && command -v brew >/dev/null 2>&1; then
  JAVA_PREFIX=$(brew --prefix openjdk@25 2>/dev/null || true)
  JAVA_HOME="$JAVA_PREFIX/libexec/openjdk.jdk/Contents/Home"
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  export JAVA_HOME
fi

[ -f "$AL" ] || { echo "missing mmm_bp.al: $AL" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
ACTUAL_SHA256=$(shasum -a 256 "$AL" | awk '{print $1}')
[ "$ACTUAL_SHA256" = ab5d98b578406c4e04fb7b546317fbec0d36ce4a7c139ef0922b06bb4b753c5b ] || {
  echo "unexpected mmm_bp.al SHA-256: $ACTUAL_SHA256" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-mmm-bp.XXXXXX")
if [ "${KEEP_WORK:-0}" = 1 ]; then
  trap 'echo "Ghidra probe workdir kept at $WORK"' EXIT
else
  trap 'rm -rf "$WORK"' EXIT
fi
mkdir -p "$WORK/project" "$WORK/home"

set +e
JAVA_TOOL_OPTIONS="-Duser.home=$WORK/home" \
  "$GHIDRA" "$WORK/project" m30mmmbp \
  -import "$AL" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript LoadAP.java "$AL" \
  -postScript DumpAt.java 0xbfc21530,0xbfc21590,0xbfc216a8,0x68021c8c,0xbfc27400,0xbfc274c8,0xbfc27520,0xbfc276ac,0x680a1ed4 "$WORK/decomp.c" \
  -postScript DumpAt.java 0xbfc277f0,0xbfc27938 "$WORK/ring-callbacks.c" \
  -postScript DumpWords.java 0xbfc21694 0xbfc21698 \
  -postScript DumpWords.java 0xbfc21698 0xbfc2169c \
  -postScript DumpWords.java 0xbfc27488 0xbfc274cc \
  -postScript DumpWords.java 0xbfc2769c 0xbfc27734 \
  -postScript DumpWords.java 0x680a2020 0x680a202c \
  -postScript FindPointerRefs.java 0x9fc21b04,0xbfc277f1,0xbfc27939 \
  -postScript DumpRefsTo.java 0x9fc21b04,0xbfc277f1,0xbfc27939 \
  -postScript DumpInstructions.java 0xbfc21a3c 8 \
  -postScript DumpInstructions.java 0xbfc21708 100 \
  -postScript DumpC.java "$WORK/all.c" \
  2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"

grep -q 'LoadAP: blocks=5 ' "$WORK/ghidra.log"
grep -q 'DumpAt: bfc21530 -> FUN_bfc21530' "$WORK/ghidra.log"
grep -q 'DumpAt: bfc21590 -> FUN_bfc21590' "$WORK/ghidra.log"
grep -q 'DumpAt: bfc216a8 -> FUN_bfc216a8' "$WORK/ghidra.log"
grep -q 'DumpAt: 68021c8c -> FUN_68021c8c' "$WORK/ghidra.log"
grep -q 'DumpAt: bfc27400 -> FUN_bfc27400' "$WORK/ghidra.log"
grep -q 'DumpAt: bfc274c8 -> FUN_bfc274c8' "$WORK/ghidra.log"
grep -q 'DumpAt: bfc27520 -> FUN_bfc27520' "$WORK/ghidra.log"
grep -q 'DumpAt: bfc276ac -> FUN_bfc276ac' "$WORK/ghidra.log"
grep -q 'DumpAt: 680a1ed4 -> FUN_680a1ed4' "$WORK/ghidra.log"
grep -q 'DumpAt: bfc277f0 -> FUN_bfc277f0' "$WORK/ghidra.log"
grep -q 'DumpAt: bfc27938 -> FUN_bfc27938' "$WORK/ghidra.log"

# The first check is a real cross-reference to the diagnostic text.  The
# second and third checks identify the adjacent decoder state/buffer paths;
# neither is interpreted as proof of the exact audio attenuation algorithm.
grep -Fq '&UNK_bfc27470' "$WORK/decomp.c"
grep -Fq '&UNK_bfc21674' "$WORK/decomp.c"
grep -Fq '&UNK_bfc21684' "$WORK/decomp.c"
grep -Fq '&UNK_bfc21864' "$WORK/decomp.c"
grep -Fq '&UNK_bfc21880' "$WORK/decomp.c"
grep -q 'param_1 + 0xba' "$WORK/decomp.c"
grep -q 'param_1 + 0x8a' "$WORK/decomp.c"
grep -q 'param_1 + 0x81' "$WORK/decomp.c"
grep -q 'param_1 + 0xc0' "$WORK/decomp.c"
grep -q 'param_1 + 0xcc' "$WORK/decomp.c"
grep -q 'param_1 + 0x81' "$WORK/decomp.c"
grep -q 'param_1 + 0x88.*< 100' "$WORK/decomp.c"
grep -q 'uVar1 + uVar2 \* -0x3c' "$WORK/decomp.c"
grep -q '\* -1000' "$WORK/decomp.c"
grep -q '\* 1000' "$WORK/decomp.c"
grep -q 'param_1\[2\] = 0x400' "$WORK/decomp.c"
grep -q 'param_3\[3\]' "$WORK/decomp.c"
grep -q 'param_1 + 0x70' "$WORK/decomp.c"
grep -q '0x200' "$WORK/decomp.c"

# These literal-pool values are the actual callback/control boundaries used
# by the decompiled routines.  The odd MIPS16 values are recorded as values,
# not treated as new patch targets: the callback body is outside this module's
# safely recovered function set.
grep -q 'WORD address=bfc21698 value=0xbfc21a3d' "$WORK/ghidra.log"
grep -q 'WORD address=bfc21694 value=0x88888889' "$WORK/ghidra.log"
grep -q 'WORD address=bfc27488 value=0xbfc21a3d' "$WORK/ghidra.log"
grep -q 'WORD address=bfc274c4 value=0xbfc21a3d' "$WORK/ghidra.log"
grep -q 'WORD address=bfc2769c value=0x9fc21b00' "$WORK/ghidra.log"
grep -q 'WORD address=bfc276a0 value=0xbfc21a3d' "$WORK/ghidra.log"
grep -q 'WORD address=bfc2772c value=0xbfc274c9' "$WORK/ghidra.log"
grep -q 'WORD address=bfc27730 value=0xbfc2748d' "$WORK/ghidra.log"
grep -q 'WORD address=680a2020 value=0x9fc21b04' "$WORK/ghidra.log"
grep -q 'WORD address=680a2024 value=0xbfc277f1' "$WORK/ghidra.log"
grep -q 'WORD address=680a2028 value=0xbfc27939' "$WORK/ghidra.log"
grep -q 'UNK_bfc2769c + 4' "$WORK/decomp.c"
grep -q 'FUN_bfc277f0' "$WORK/ring-callbacks.c"
grep -q 'FUN_bfc27938' "$WORK/ring-callbacks.c"
grep -Fq 'puVar11 = *(ushort **)(param_1 + 0x70);' "$WORK/ring-callbacks.c"
grep -Fq 'puVar3 = *(ushort **)(param_1 + 0x70);' "$WORK/ring-callbacks.c"
grep -Fq 'iVar5 = *(int *)(puVar3 + 4);' "$WORK/ring-callbacks.c"
grep -Fq 'puVar11[1] = (ushort)in_hi;' "$WORK/ring-callbacks.c"
grep -Fq 'puVar3[1] = (ushort)in_hi;' "$WORK/ring-callbacks.c"
grep -q 'POINTER_REF value=0x9fc21b04 address=680a2020 owner=<no-function>' "$WORK/ghidra.log"
grep -q 'POINTER_REF value=0xbfc277f1 address=680a2024 owner=<no-function>' "$WORK/ghidra.log"
grep -q 'POINTER_REF value=0xbfc27939 address=680a2028 owner=<no-function>' "$WORK/ghidra.log"
grep -q 'REF target=9fc21b04 from=680a1f4e type=WRITE function=FUN_680a1ed4' "$WORK/ghidra.log"
grep -q 'REF target=9fc21b04 from=680a1ffc type=WRITE function=FUN_680a1ed4' "$WORK/ghidra.log"
grep -q 'REF target=bfc277f1 from=680a1f4e type=DATA function=FUN_680a1ed4' "$WORK/ghidra.log"
grep -q 'REF target=bfc27939 from=680a1ffc type=DATA function=FUN_680a1ed4' "$WORK/ghidra.log"
grep -Fq 'case 0xb:' "$WORK/all.c"
grep -Fq "if ((char)param_1[3] == '\\0') {" "$WORK/all.c"
grep -Fq "if ((char)param_1[3] == '\\x01') {" "$WORK/all.c"
grep -Fq 'param_1[2] = 0x80;' "$WORK/all.c"
grep -Fq 'param_1[2] = 0x400;' "$WORK/all.c"

# The unresolved callback target is recorded at instruction level.  A missing
#/bad decode here is evidence to keep it outside the patch model, not a reason
# to invent a function prototype.
grep -q 'INSN address=bfc21a3c' "$WORK/ghidra.log"
grep -q 'INSN address=bfc21a3e bytes=05 e8 break' "$WORK/ghidra.log"

# In the active decoder-state path, ring/output accounting runs before the
# state-3 terminal branch. That branch returns before the 0x200-byte refill
# loop's back-edge can run. This pins the ordering boundary; it does not prove
# that a DAC/ASRC queue is empty.
grep -q 'INSN address=bfc21712 .*jal 0xbfc276ac' "$WORK/ghidra.log"
grep -q 'INSN address=bfc21794 .*jal 0xbfc27520' "$WORK/ghidra.log"
grep -q 'INSN address=bfc217a2 bytes=03 71 cmpi s1' "$WORK/ghidra.log"
grep -q 'INSN address=bfc217a4 bytes=07 60 bteqz 0xbfc217b4' "$WORK/ghidra.log"
grep -q 'INSN address=bfc217b2 bytes=aa 61 btnez 0xbfc21708' "$WORK/ghidra.log"
grep -q 'INSN address=bfc217b6 bytes=02 6e li a2' "$WORK/ghidra.log"
grep -q 'INSN address=bfc217ba bytes=c7 d8 sw a2' "$WORK/ghidra.log"
grep -q 'INSN address=bfc217c8 bytes=a0 e8 jrc ra' "$WORK/ghidra.log"
grep -Fq 'if ((iVar6 == 3) ||' "$WORK/all.c"
grep -Fq 'iVar6 = **(int **)(iVar4 + 0xd0);' "$WORK/decomp.c"
grep -Fq '100 < (uint)(*(int *)(iVar4 + 0xec) - *(int *)(iVar4 + 0xf0))' "$WORK/all.c"
grep -Fq 'FUN_bfc276ac(iVar9);' "$WORK/all.c"
if [ "$(grep -Fc 'iVar6 = **(int **)(iVar4 + 0xd0);' "$WORK/all.c")" -ne 2 ]; then
  echo 'whole-module decompile no longer shows exactly two explicit context+0xd0 reads' >&2
  grep -n -C 2 '0xd0' "$WORK/all.c" >&2 || true
  exit 1
fi
if grep -Eq '\*\([^)]*\)\([^)]*\+ 0xd0\)[[:space:]]*=' "$WORK/all.c"; then
  echo 'whole-module decompile shows an explicit context+0xd0 store' >&2
  grep -n -C 2 '0xd0' "$WORK/all.c" >&2 || true
  exit 1
fi

# DumpC covers every recovered function in this image.  The fade-control byte
# is only read/cleared in the mapped set; no mapped function writes the value
# that arms the fade transition, so that setter remains outside this image's
# recovered call graph.
# Includes the two callback targets explicitly decompiled above.
prologue_functions=$(grep -Eo 'LoadAP: blocks=5 prologue functions=[0-9]+' "$WORK/ghidra.log" | tail -1)
prologue_count=${prologue_functions##*=}
[ "$prologue_count" -eq 30 ] || {
  echo "LoadAP seeded-function count changed: ${prologue_count:-missing}" >&2
  exit 1
}
dumpc_functions=$(grep -Eo 'DumpC: functions=[0-9]+' "$WORK/ghidra.log" | tail -1)
dumpc_count=${dumpc_functions##*=}
[ "$dumpc_count" -ge "$prologue_count" ] || {
  echo "whole-module DumpC pass covered fewer than the seeded functions: ${dumpc_count:-missing}" >&2
  exit 1
}
if grep -Fq '// failed:' "$WORK/all.c"; then
  echo 'whole-module +0xd0 scan is incomplete because DumpC failed to decompile a function' >&2
  grep -n -B 1 -A 1 -F '// failed:' "$WORK/all.c" >&2
  exit 1
fi
grep -q '^// ===== FUN_680a1ed4 ' "$WORK/all.c"
grep -q '^// ===== FUN_bfc21530 ' "$WORK/all.c"
[ "$(grep -c '+ 0x81' "$WORK/all.c")" -eq 4 ]
grep -Fq '((int)param_1 + 0x81) = 0' "$WORK/all.c"
grep -Fq 'param_1 + 0x81) = 0' "$WORK/all.c"

echo 'MMM BP FADE PASS diagnostic=fade-out/in buffer-state=verified attenuation=unresolved'
