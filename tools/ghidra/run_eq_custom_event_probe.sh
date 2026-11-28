#!/usr/bin/env bash
# Trace the MCG Custom callback's 0x8000 request into its shared music.ap target.
# Static Ghidra analysis only; no firmware is modified or executed on-device.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
MUSIC=${1:-$ROOT/analysis/community/fw/music.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
EXPECTED_SHA256=662e379602707a9c4b0dcd6b94b591dd77c2b5f7a557fcb03b8c2a5c9b1baac3

[ -f "$MUSIC" ] || { echo "missing music.ap: $MUSIC" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
actual_sha256=$(shasum -a 256 "$MUSIC" | awk '{print $1}')
[ "$actual_sha256" = "$EXPECTED_SHA256" ] || {
  echo "music.ap SHA-256 $actual_sha256 != $EXPECTED_SHA256" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-eq-custom-event.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30eqcustomevent \
    -import "$MUSIC" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$MUSIC" \
    -postScript DumpAt.java 0x40414c00,0x48ad47b0 "$WORK/decomp.c" containing \
    -postScript FindPointerRefs.java 0x48ad47b1,0x9fc15706,0x9fc15707 \
    >"$WORK/run.log" 2>&1 || {
      cat "$WORK/run.log" >&2
      exit 1
    }

for marker in \
  'DumpAt: 40414c00 -> FUN_40414c00' \
  'DumpAt: 48ad47b0 -> FUN_48ad47b0' \
  'POINTER_REF value=0x48ad47b1 address=40414c70' \
  'POINTER_REF value=0x9fc15706 address=40414c68' \
  'POINTER_REF value=0x9fc15707 address=40414c64'; do
  grep -Fq "$marker" "$WORK/run.log" || {
    echo "missing trace marker: $marker" >&2
    cat "$WORK/run.log" >&2
    exit 1
  }
done

for marker in \
  'uStack_20 = 3;' \
  'uStack_1f = 5;' \
  'uStack_1e = 0xffff;' \
  '(*_UNK_40414c70)(0x8000,&uStack_20);' \
  'return -0x7fec;'; do
  grep -Fq "$marker" "$WORK/decomp.c" || {
    echo "missing decompiler marker: $marker" >&2
    cat "$WORK/decomp.c" >&2
    exit 1
  }
done

grep -Fq "if (*param_2 != '\\x02')" "$WORK/decomp.c" || {
  echo "missing unsupported-payload branch in target" >&2
  cat "$WORK/decomp.c" >&2
  exit 1
}

echo "EQ CUSTOM EVENT PROBE PASS callback=0x40414c00 request=0x8000 target=0x48ad47b0 payload-type=3 rejected=1 result=-0x7fec"
