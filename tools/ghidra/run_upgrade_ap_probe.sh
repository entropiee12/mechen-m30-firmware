#!/usr/bin/env bash
# Recover only the evidence-backed upgrade-screen boundary from upgrade.ap.
# This is static analysis: it never executes the AP and never writes firmware.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:-$ROOT/analysis/community/fw/upgrade.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}

[ -f "$AP" ] || { echo "missing upgrade AP: $AP" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-upgrade-ap.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project"

set +e
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30upgrade \
  -import "$AP" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript LoadAP.java "$AP" \
  -postScript DumpAt.java '0x40014c30,0x40014d54,0x40054c60,0x40094c00,0x40094e3c,0xbfc37408' "$WORK/flow.c" \
  -postScript DumpWords.java 0x40014e88 0x40014ea0 \
  -postScript DumpWords.java 0x40055078 0x4005507c \
  -postScript DumpWords.java 0xbfc3b030 0xbfc3b034 \
  2>&1 | tee "$WORK/ghidra.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"
if grep -q 'ERROR REPORT SCRIPT ERROR' "$WORK/ghidra.log"; then
  echo "Ghidra script reported an error; upgrade probe is not considered a pass" >&2
  exit 1
fi

# These checks are deliberately narrow: they assert the screen loop and
# resource names, but do not assign meanings to unresolved indirect calls.
grep -q 'DumpAt: 40014c30 ->' "$WORK/ghidra.log"
grep -q 'DumpAt: 40014d54 ->' "$WORK/ghidra.log"
grep -q 'DumpAt: bfc37408 ->' "$WORK/ghidra.log"
grep -Fq 'bStack_10 < 5' "$WORK/flow.c"
grep -Fq 'uStack_30 = 0x2001' "$WORK/flow.c"
grep -Fq 'uStack_30 = 0x2000' "$WORK/flow.c"
grep -Fq '(*_UNK_40055078)(0xf,_UNK_40055074,0,6)' "$WORK/flow.c"
grep -Fq 'WORD address=40014e88 value=0x72677075' "$WORK/ghidra.log"
grep -Fq 'WORD address=40014e94 value=0x6d6d6f63' "$WORK/ghidra.log"
grep -Fq 'WORD address=40055078 value=0xbfc3b031' "$WORK/ghidra.log"
grep -Fq 'WORD address=bfc3b030 value=0xe8056b00' "$WORK/ghidra.log"
echo "UPGRADE AP PROBE PASS entry=0x40014c30 screen-loop=5 module=0x00 arg0=0x0f pointer=0xbfc3b031"
