#!/usr/bin/env bash
# Map and decompile the public ATJ2127 UI ROM reference image.
# This is a reference-only probe: it does not read or modify an M30 device,
# produce a firmware image, or execute code from the input binary.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ROM=${1:-/private/tmp/PD196_ATJ2127/case/drv/ui/UI0_ROM0.BIN}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}

[ -f "$ROM" ] || { echo "missing reference UI ROM: $ROM" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
[ "$(wc -c < "$ROM")" -eq $((0xc00)) ] || {
  echo "reference UI ROM must be exactly 0xc00 bytes" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-reference-uirom.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project" "$WORK/home" "$WORK/cache" "$WORK/tmp"
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}"
GHIDRA_JAVA_OPTIONS="$GHIDRA_JAVA_OPTIONS -Dapplication.cachedir=$WORK/cache -Dapplication.tempdir=$WORK/tmp"

set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="$GHIDRA_JAVA_OPTIONS" \
  "$GHIDRA" "$WORK/project" referenceuirom \
  -import "$ROM" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript FlatLoad.java "$ROM" 0xbfc0b400 0 0xc00 \
  -postScript DumpAt.java \
    0xbfc0b5ac,0xbfc0b618,0xbfc0b6cc,0xbfc0b78c,0xbfc0b85c,0xbfc0b964,0xbfc0bc04,0xbfc0bef4 \
    "$WORK/decomp.c" \
  >"$WORK/ghidra.log" 2>&1
GHIDRA_STATUS=$?
set -e
[ "$GHIDRA_STATUS" -eq 0 ] || { cat "$WORK/ghidra.log" >&2; exit "$GHIDRA_STATUS"; }

grep -q 'FlatLoad: bytes=3072 functions=16 base=0xbfc0b400' "$WORK/ghidra.log"
for address in bfc0b5ac bfc0b618 bfc0b6cc bfc0b78c bfc0b85c bfc0b964 bfc0bc04 bfc0bef4; do
  grep -q "DumpAt: $address -> FUN_$address" "$WORK/ghidra.log"
  grep -q "FUN_$address" "$WORK/decomp.c"
done

# Keep the probe tied to observable shapes rather than inferred function names.
# Extracting sections avoids accepting a marker that belongs to a neighboring
# function if Ghidra changes its local-variable naming or output layout.
extract_section() {
  local name=$1 start=$2 end=$3
  awk -v start="$start" -v end="$end" \
    '$0 ~ start {show=1} (end != "" && $0 ~ end) {show=0} show' \
    "$WORK/decomp.c" > "$WORK/$name.c"
}

extract_section sdimage FUN_bfc0b618 FUN_bfc0b6cc
extract_section fill FUN_bfc0b6cc FUN_bfc0b78c
extract_section readback FUN_bfc0b964 FUN_bfc0ba8c
extract_section put_string FUN_bfc0bc04 FUN_bfc0bef4
extract_section length FUN_bfc0bef4 ''

grep -Fq 'uVar4 = (uint)param_3[2] * (uint)param_3[3] * 2;' "$WORK/sdimage.c"
grep -Fq '(*_UNK_bfc0b6c4)(param_1,0,param_2,0xf);' "$WORK/sdimage.c"
grep -Fq '(*_UNK_bfc0b6c4)(param_1,uVar3,uVar2,0x11);' "$WORK/sdimage.c"
grep -Fq 'param_2 & 0xffff' "$WORK/fill.c"
grep -Fq 'uVar5 = (uint)param_1[2] * (uint)param_1[3] * 2;' "$WORK/fill.c"
grep -Fq "if (param_3 == '\\x01')" "$WORK/readback.c"
grep -Fq '(*(code *)*_UNK_bfc0b9c0)(param_1,param_2,param_3);' "$WORK/readback.c"
grep -Fq 'uStack_38 = param_3 & 0xff;' "$WORK/put_string.c"
grep -Fq '(*pcVar5)(uStack_30,puVar8,uStack_38);' "$WORK/put_string.c"
grep -Fq 'uStack_24 = param_2 & 0xffff;' "$WORK/length.c"
grep -Fq '*param_3 = 0;' "$WORK/length.c"
grep -Fq 'uStack_18 = uStack_18 + ' "$WORK/length.c"

echo 'REFERENCE UI ROM PROBE PASS base=0xbfc0b400 size=0xc00 image=bfc0b5ac sd-image=bfc0b618 fill=bfc0b6cc readback=bfc0b964 put-string=bfc0bc04 length=bfc0bef4'
