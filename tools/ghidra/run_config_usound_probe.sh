#!/usr/bin/env bash
# Prove the static USB-mode selector and its config default. Read-only only.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
FW=${1:-$ROOT/analysis/community/fw}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}

[ -f "$FW/config.ap" ] || { echo "missing config.ap in $FW" >&2; exit 1; }
[ -f "$FW/config.bin" ] || { echo "missing config.bin in $FW" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

PYTHONPATH="$ROOT/tools/emu" python3 "$ROOT/tools/emu/config_usb_mode_probe.py" "$FW/config.bin"

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-config-usound.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project"

set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30configusound \
  -import "$FW/config.ap" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript LoadAP.java "$FW/config.ap" \
  -postScript DumpAt.java 0x40054c00 "$WORK/msg.c" \
  -postScript DumpAt.java 0x40054ccc "$WORK/init.c" 2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"

grep -q 'DumpAt: 40054c00' "$WORK/ghidra.log"
grep -q 'DumpAt: 40054ccc' "$WORK/ghidra.log"
grep -Fq 'if (*_UNK_40054cbc == '\''\x01'\'')' "$WORK/msg.c"
grep -Fq 'uStack_14 = 0x12;' "$WORK/msg.c"
grep -Fq 'uStack_14 = 0xc;' "$WORK/msg.c"
grep -Fq 'uStack_14 = 0x10;' "$WORK/msg.c"
grep -Fq 'uStack_18 = 0x201;' "$WORK/msg.c"
grep -Fq 'uVar3 = (*_UNK_40054eb8)(0x116);' "$WORK/init.c"
echo 'USB MODE CONFIG PASS msg=0x90 usound=0x12 udisk=0xc mtp=0x10 item=0x116 default-flag=0'
