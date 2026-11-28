#!/usr/bin/env bash
# Reproduce the extracted ker_xsys exception-vector inspection.
# This is a static Ghidra probe; it does not emulate the missing ROM handler.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
XSYS=${1:-$ROOT/analysis/community/fw/ker_xsys.bin}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}

[ -f "$XSYS" ] || { echo "missing ker_xsys: $XSYS" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-kerxsys.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project"

set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" kerxsys \
  -import "$XSYS" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript MapSegments.java 1 "$XSYS" 0xbfc34000 0 0x800 0 \
  -postScript DumpAt.java 0xbfc34074,0xbfc340a4 "$WORK/decomp.c" 2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"

grep -q '0xbfc06d6c' "$WORK/decomp.c"
grep -q '0x3ffff' "$WORK/decomp.c"
grep -Fq 'uRam00013dd8 = EPC;' "$WORK/decomp.c"
grep -Fq 'uRam00013ddc = unaff_retaddr;' "$WORK/decomp.c"
grep -Fq '(*(code *)0xbfc06d6c)();' "$WORK/decomp.c"
grep -Fq '((uint)auStackX_0 & 0x3ffff)' "$WORK/decomp.c"
echo 'KERNEL EXCEPTION PASS epc_handler=0xbfc06d6c mask=0x3ffff'
