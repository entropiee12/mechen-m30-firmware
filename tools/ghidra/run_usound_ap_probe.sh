#!/usr/bin/env bash
# Prove the usound AP's conditional driver-install call. Static only.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:-$ROOT/analysis/community/fw/usound.ap}
MANAGER_AP=${2:-}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}

[ -f "$AP" ] || { echo "missing usound AP: $AP" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

PYTHONPATH="$ROOT/tools/emu" python3 "$ROOT/tools/emu/usound_ap_binary_probe.py" "$AP"

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-usound-ap.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project"

set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30usoundap \
  -import "$AP" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript LoadAP.java "$AP" \
  -postScript FindPointerRefs.java 0xbfc166ad \
  -postScript DumpAt.java 0x40054c00,0x40054de0,0x40094c00,0x40094e88,0x400950c0,0x48494640,0x48494758 "$WORK/app.c" 2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"

grep -q 'LoadAP: blocks=15 prologue functions=87 entry=40014c30' "$WORK/ghidra.log"
for address in 40054cac 40094cc8 4009507c 40095208; do
  grep -Fq "POINTER_REF value=0xbfc166ad address=$address " "$WORK/ghidra.log"
done
grep -Fq 'POINTER_TOTAL value=0xbfc166ad count=4' "$WORK/ghidra.log"
grep -q 'DumpAt: 40054c00 -> FUN_40054c00' "$WORK/ghidra.log"
grep -q 'DumpAt: 40094c00 -> FUN_40094c00' "$WORK/ghidra.log"
grep -q 'DumpAt: 40054de0 -> FUN_40054de0' "$WORK/ghidra.log"
grep -q 'DumpAt: 40094e88 -> FUN_40094e88' "$WORK/ghidra.log"
grep -q 'DumpAt: 400950c0 -> FUN_400950c0' "$WORK/ghidra.log"
grep -q 'DumpAt: 48494640 -> FUN_48494640' "$WORK/ghidra.log"
grep -q 'DumpAt: 48494758 -> FUN_48494758' "$WORK/ghidra.log"
grep -Fq 'uStack_10 = 0x201;' "$WORK/app.c"
grep -Fq '(*_UNK_40054cac)(0,auStack_18,6,3);' "$WORK/app.c"
grep -Fq '(*_UNK_40054cac)(5,auStack_18,1,3);' "$WORK/app.c"
grep -Fq '(*_UNK_40094cc8)(1,8,0,6);' "$WORK/app.c"
grep -Fq '(*_UNK_4009507c)(_UNK_40095078,0,0,1);' "$WORK/app.c"
grep -Fq '(*_UNK_4009507c)(0,0,0,2);' "$WORK/app.c"
grep -Fq '(*_UNK_40095208)(0,0,0,7);' "$WORK/app.c"
grep -Fq '(*_UNK_40095208)(5,0,0,6);' "$WORK/app.c"
grep -Fq '(*_UNK_40054f04)(8,&uStack_10);' "$WORK/app.c"
grep -Fq 'uVar1 = 0x861c;' "$WORK/app.c"
grep -Fq 'uVar1 = 0x800f;' "$WORK/app.c"
grep -Fq 'uStack_10 = (*_UNK_40095088)(param_1);' "$WORK/app.c"
grep -Fq 'uStack_18 = (*_UNK_40095228)(param_1);' "$WORK/app.c"
grep -Fq 'iVar1 = FUN_48494640(param_1);' "$WORK/app.c"
grep -Fq 'if (param_1 < 0x13)' "$WORK/app.c"
grep -Fq "(byte)pcVar1[1] == param_1" "$WORK/app.c"
grep -Fq 'uVar2 < 3' "$WORK/app.c"
grep -Fq '(*_UNK_48494790)(auStack_20,param_2,8,0);' "$WORK/app.c"
grep -Fq '(*_UNK_48494794)(*(undefined1 *)(iVar1 + 3),auStack_20,0,0x18);' "$WORK/app.c"
grep -Fq '(*_UNK_40095064)(&UNK_40095014,0,0,0);' "$WORK/app.c"
grep -Fq '(*_UNK_40095064)(&UNK_40095020,1,0,0);' "$WORK/app.c"
grep -Fq 'iVar1 = (*_UNK_4009505c)(5,0,&UNK_4009502c,10);' "$WORK/app.c"
grep -Fq '(*_UNK_4009505c)(&UNK_40095038,0,0,0x4b);' "$WORK/app.c"
if [ -n "$MANAGER_AP" ]; then
  PYTHONPATH="$ROOT/tools/emu" python3 "$ROOT/tools/emu/usound_manager_ap_binary_probe.py" "$MANAGER_AP"
  mkdir -p "$WORK/manager-project"
  set +e
  JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
  GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
    "$GHIDRA" "$WORK/manager-project" m30manager \
    -import "$MANAGER_AP" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$MANAGER_AP" \
    -postScript DumpAt.java 0x7f196200 "$WORK/manager.c" 2>&1 | tee "$WORK/manager-ghidra.log"
  STATUS=${PIPESTATUS[0]}
  set -e
  [ "$STATUS" -eq 0 ] || exit "$STATUS"
  grep -q 'DumpAt: 7f196200 -> FUN_7f196200' "$WORK/manager-ghidra.log"
  grep -Fq 'iStack_c = *param_1 + -0x201;' "$WORK/manager.c"
  grep -Fq 'case 0:' "$WORK/manager.c"
  grep -Fq 'case 1:' "$WORK/manager.c"
  grep -Fq 'case 3:' "$WORK/manager.c"
  grep -Fq 'case 5:' "$WORK/manager.c"
  grep -Fq 'case 6:' "$WORK/manager.c"
  echo 'USOUND->MANAGER ROUTE CANDIDATE PASS sender=app-id-8 manager-handler=0x7f196200 message=0x201'
fi
echo 'USOUND AP CALLBACK PASS slots=0x40095088,0x40095228 entry=0x40054de0 message=0x201'
echo 'USOUND AP MESSAGE SENDER PASS destination=app-id-8 sender=0x48494758 resident-calls=0xbfc16405,0xbfc16401'
echo 'USOUND AP DRIVER PATH PASS app=0x40094e88 driver-name=0x4009502c install-error=0x40095038'
