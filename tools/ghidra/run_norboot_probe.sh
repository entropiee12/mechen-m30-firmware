#!/usr/bin/env bash
# Map the extracted NORBREC member with its candidate mixed MIPS32/MIPS16 mode
# boundaries and verify the recovered MIPS16 calls to an unavailable ROM helper.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
NORBREC=${1:?usage: run_norboot_probe.sh NORBREC.BIN}
GHIDRA=${GHIDRA:-$(command -v analyzeHeadless || true)}
NORBREC_SHA256=8b11f25209e6a79474559b4b48d3a9d7f29d5f07633d234fe0eada9b8cd4e93d

[ -f "$NORBREC" ] || { echo "missing NORBREC: $NORBREC" >&2; exit 1; }
[ -n "$GHIDRA" ] && [ -x "$GHIDRA" ] || {
  echo "analyzeHeadless not found; install Ghidra or set GHIDRA to its executable path" >&2
  exit 1
}
actual_sha=$(shasum -a 256 "$NORBREC" | awk '{print $1}')
[ "$actual_sha" = "$NORBREC_SHA256" ] || {
  echo "NORBREC SHA-256 $actual_sha != $NORBREC_SHA256" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-norboot.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project" "$WORK/javatmp"

require_match() {
  local file=$1
  local pattern=$2
  if ! grep -qE -- "$pattern" "$file"; then
    echo "missing expected output pattern: $pattern in $file" >&2
    cat "$file" >&2
    exit 1
  fi
}

GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home -Djava.io.tmpdir=$WORK/javatmp}" \
  "$GHIDRA" "$WORK/project" m30norboot \
    -import "$NORBREC" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript MapNorBoot.java "$NORBREC" 0xbfc31f00 \
    -postScript RunMipsNativeModeProbe.java \
    -postScript RunNorBootCallbackBridgeProbe.java \
    -postScript RunNorBootSetupProbe.java \
    -postScript RunNorBootReaderProbe.java \
    -postScript DumpC.java "$WORK/norboot.c" \
    >"$WORK/run.log" 2>&1 || {
      cat "$WORK/run.log" >&2
      exit 1
    }

grep -E '(MIPS NATIVE MODE PASS|NORBOOT (CALLBACK BRIDGE|ROM EDGE|READER EDGE|SETUP P-CODE PASS|READER P-CODE PASS))' "$WORK/run.log"
require_match "$WORK/run.log" 'NORBOOT READER P-CODE PASS'
require_match "$WORK/run.log" 'MIPS NATIVE MODE PASS'
require_match "$WORK/run.log" 'NORBOOT CALLBACK BRIDGE PASS function=0xbfc32058 slot=0xbfc02608 a2-0x28-calls=1 a2-0x29-calls=0 callback-body=stubbed'
require_match "$WORK/norboot.c" 'FUN_bfc3292c @ bfc3292c'
require_match "$WORK/norboot.c" 'FUN_bfc32a48 @ bfc32a48'
require_match "$WORK/norboot.c" 'func_0xbfc108d8\(param_1,uVar1,param_3\)'
require_match "$WORK/norboot.c" 'func_0xbfc108d8\(uVar5,puVar2,0x14\)'
require_match "$WORK/norboot.c" 'func_0xbfc108d8\(_UNK_bfc32ab8,_UNK_bfc32abc,0x20\)'
require_match "$WORK/run.log" 'NORBOOT SETUP P-CODE PASS entry=0xbfc32a48.*native-mips-mode=true'
require_match "$WORK/run.log" 'NORBOOT READER P-CODE PASS entry=0xbfc3292c.*native-mips-mode=true.*length=0x601 rounded-blocks=4'

echo "NORBOOT PROBE PASS callback-bridge=0xbfc32058 reader=0xbfc3292c setup=0xbfc32a48 dynamic-edges=6"
