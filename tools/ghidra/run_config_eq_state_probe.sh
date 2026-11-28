#!/usr/bin/env bash
# Reproduce the M30 config.ap persistent-block and EQ-buffer copy evidence.
# This is static Ghidra analysis; it does not write firmware or device state.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:-$ROOT/analysis/community/fw/config.ap}
EXPECTED_SHA256=5b5687c00880e691500c7abbfba43dab51ae0f95d2203f74aced2e8d56357580
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}

[ -f "$AP" ] || { echo "missing config AP: $AP" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

actual_sha256=$(shasum -a 256 "$AP" | awk '{print $1}')
[ "$actual_sha256" = "$EXPECTED_SHA256" ] || {
  echo "config.ap SHA-256 $actual_sha256 != $EXPECTED_SHA256" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-config-eq-state.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30configeqstate \
    -import "$AP" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$AP" \
    -postScript DumpAt.java 0x40054ccc,0x40054f38,0x40dd4c00 "$WORK/config.c" containing \
    -postScript FindPointerRefs.java 0x9fc15654,0x9fc15680,0x9fc15700,0x9fc1a19c \
    -postScript RunConfigEqDefaultProbe.java \
    >"$WORK/run.log" 2>&1

for marker in \
  'DumpAt: 40054ccc -> FUN_40054ccc @ 40054ccc' \
  'DumpAt: 40054f38 -> FUN_40054f38 @ 40054f38' \
  'DumpAt: 40dd4c00 -> FUN_40dd4c00 @ 40dd4c00' \
  'POINTER_TOTAL value=0x9fc15680 count=1' \
  'POINTER_TOTAL value=0x9fc1a19c count=1' \
  'CONFIG EQ DEFAULT P-CODE PASS'; do
  if ! grep -q "$marker" "$WORK/run.log"; then
    echo "missing Ghidra log marker: $marker" >&2
    cat "$WORK/run.log" >&2
    exit 1
  fi
done

for marker in \
  '(*_UNK_40054e9c)(_UNK_40054e94,0,0x38,1)' \
  '(*_UNK_40054ea0)(_UNK_40054e94)' \
  '(*_UNK_40054e98)(_UNK_40054e94,0,0x38,0x12)' \
  '(*_UNK_40054e98)(_UNK_40054ea8,_UNK_40054eac,0xf0,0x12)' \
  '(*_UNK_40054e9c)(_UNK_40054eec,_UNK_40054ef0,7,0)' \
  '(*_UNK_40054f70)(_UNK_40054f6c,0,0x38,0x13)' \
  '(*_UNK_40054f70)(_UNK_40054f74,_UNK_40054f78,0xf0,0x13)'; do
  if ! grep -Fq "$marker" "$WORK/config.c"; then
    echo "missing decompiler marker: $marker" >&2
    cat "$WORK/config.c" >&2
    exit 1
  fi
done

echo "CONFIG EQ STATE PROBE PASS sha256=$actual_sha256 blocks=0x38,0xf0 custom_copy=7B cold_tail=untouched"
