#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
XSYS=${1:?usage: run_ker_xsys_exception_frame_probe.sh KER_XSYS.BIN}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}
EXPECTED_SHA256=deaf67edce7eca6397abd1ca91f742ccbe70f451bb4926c75c7e46862e3b2988

[ -f "$XSYS" ] || { echo "missing ker_xsys.bin: $XSYS" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
INPUT_SHA=$(shasum -a 256 "$XSYS" | awk '{print $1}')
[ "$INPUT_SHA" = "$EXPECTED_SHA256" ] || {
  echo "ker_xsys.bin SHA-256 $INPUT_SHA != $EXPECTED_SHA256" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-xsys-frame.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project"

JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" xsysframe \
  -import "$XSYS" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript MapSegments.java 1 "$XSYS" 0xbfc34000 0 0x800 1 \
  -postScript DumpAt.java 0xbfc3416c "$WORK/stack-diagnostic.c" \
  -postScript RunKerXsysExceptionFrameProbe.java "$XSYS" \
  >"$WORK/run.log" 2>&1 || {
    cat "$WORK/run.log" >&2
    exit 1
  }

grep -Fq 'KER XSYS EXCEPTION FRAME PROFILE PASS state=0x00000000 frame-words=5 mmio-write=0x5d' "$WORK/run.log" || {
  cat "$WORK/run.log" >&2
  exit 1
}
grep -Fq 'KER XSYS EXCEPTION FRAME PROFILE PASS state=0x00000002 frame-words=5 mmio-write=0x69' "$WORK/run.log" || {
  cat "$WORK/run.log" >&2
  exit 1
}
grep -Fq 'KER XSYS EXCEPTION FRAME P-CODE PASS entry=0xbfc344f4 frame=0x00013dcc profiles=2 callback=0x00070001 synthetic-rom-callback=1' "$WORK/run.log" || {
  cat "$WORK/run.log" >&2
  exit 1
}
grep -Fq 'KER XSYS STACK GUARD SENTINEL P-CODE PASS callbacks=2 first=(0xbfc341d0,0x00000200,2) second=(0xbfc341d4,0x00000055,2) handler=0xbfc19a74 distance=0x180 synthetic-rom-services=1' "$WORK/run.log" || {
  cat "$WORK/run.log" >&2
  exit 1
}
grep -Fq 'KER XSYS STACK GUARD THRESHOLD P-CODE PASS stack=0x00000113 distance=0x093 outcome=fail callbacks=2 synthetic-rom-services=1' "$WORK/run.log" || {
  cat "$WORK/run.log" >&2
  exit 1
}
grep -Fq 'KER XSYS STACK GUARD THRESHOLD P-CODE PASS stack=0x00000114 distance=0x094 outcome=pass callbacks=0 synthetic-rom-services=1' "$WORK/run.log" || {
  cat "$WORK/run.log" >&2
  exit 1
}
grep -Fq 'pcVar1 = _UNK_bfc341f8;' "$WORK/stack-diagnostic.c" || {
  cat "$WORK/stack-diagnostic.c" >&2
  exit 1
}
grep -Fq '(*_UNK_bfc341f8)(&UNK_bfc341d0,uVar3,2);' "$WORK/stack-diagnostic.c" || {
  cat "$WORK/stack-diagnostic.c" >&2
  exit 1
}
grep -Fq '(*pcVar1)(&UNK_bfc341d4,param_2,2);' "$WORK/stack-diagnostic.c" || {
  cat "$WORK/stack-diagnostic.c" >&2
  exit 1
}
grep -Fq 'func_0xbfc19a74();' "$WORK/stack-diagnostic.c" || {
  cat "$WORK/stack-diagnostic.c" >&2
  exit 1
}
echo 'KER XSYS STACK DIAGNOSTIC DECOMP PASS callback=0xbfc04840 labels=sp/stk-overflow'
grep -E 'KER XSYS (EXCEPTION FRAME|STACK GUARD)' "$WORK/run.log"
AFTER_SHA=$(shasum -a 256 "$XSYS" | awk '{print $1}')
[ "$INPUT_SHA" = "$AFTER_SHA" ] || {
  echo "input SHA-256 changed: before=$INPUT_SHA after=$AFTER_SHA" >&2
  exit 1
}
echo "KER XSYS INPUT SHA256=$AFTER_SHA"
