#!/usr/bin/env bash
# Decompile the resident USB mass-storage host boundary. Static analysis only.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/uhost.drv}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}

[ -f "$DRIVER" ] || { echo "missing USB-host driver: $DRIVER" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

PYTHONPATH="$ROOT/tools/emu" python3 "$ROOT/tools/emu/uhost_binary_probe.py" "$DRIVER"

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-uhost.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project"

# file 0x200 is loaded at 0xbfc17400, so file byte zero is bfc17200.
set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30uhost \
  -import "$DRIVER" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript FlatLoad.java "$DRIVER" 0xbfc17200 0x200 0x798 \
  -postScript DumpC.java "$WORK/uhost.c" 2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"

grep -q 'FlatLoad: bytes=1944 functions=10' "$WORK/ghidra.log"
for address in bfc17420 bfc1752c bfc17640 bfc1777c bfc17a08 bfc17afc bfc17b1c bfc17b64; do
  grep -q "FUN_$address" "$WORK/uhost.c"
done
grep -q '0x200' "$WORK/uhost.c"
grep -q '0x1f' "$WORK/uhost.c"
grep -Fq '*(undefined1 *)(_UNK_bfc17634 + 0xc) = 0x80;' "$WORK/uhost.c"
grep -Fq '*(undefined1 *)(iVar1 + 0xf) = 0x28;' "$WORK/uhost.c"
grep -Fq '*(undefined1 *)(iVar1 + 0xf) = 0x2a;' "$WORK/uhost.c"
grep -Fq '*(uint *)(iVar4 + 8) = param_2 << 9;' "$WORK/uhost.c"
grep -q 'iVar10 = 0x83' "$WORK/uhost.c"
grep -q 'iVar10 = 0x84' "$WORK/uhost.c"
echo 'USB HOST DRIVER PASS functions=10 mass-storage-cbw=0x28/0x2a transfer=512-byte-units audio-class=not-present-in-member'
