#!/usr/bin/env bash
# Execute the real usound callback/sender code with explicitly synthetic resident IPC.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:-$ROOT/analysis/community/fw/usound.ap}
MANAGER_AP=${2:-$ROOT/analysis/community/fw/manager.ap}
EXPECTED_SHA256=e67af2588eea49bf1f81a588ac252636e3678a756d39013e46605cc16afa96cf
EXPECTED_MANAGER_SHA256=d94d2406d5e88efee1ffacff2d399ce7d865410ac7458efdb9f185eae359af7a
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}

[ -f "$AP" ] || { echo "missing community usound.ap: $AP" >&2; exit 1; }
[ -f "$MANAGER_AP" ] || { echo "missing community manager.ap: $MANAGER_AP" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
actual_sha256=$(shasum -a 256 "$AP" | awk '{print $1}')
[ "$actual_sha256" = "$EXPECTED_SHA256" ] || {
  echo "usound.ap SHA-256 $actual_sha256 != $EXPECTED_SHA256" >&2
  exit 1
}
actual_manager_sha256=$(shasum -a 256 "$MANAGER_AP" | awk '{print $1}')
[ "$actual_manager_sha256" = "$EXPECTED_MANAGER_SHA256" ] || {
  echo "manager.ap SHA-256 $actual_manager_sha256 != $EXPECTED_MANAGER_SHA256" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-usound-message.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project" "$WORK/manager-project"

JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30usoundmessage \
    -import "$AP" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$AP" \
    -postScript RunUsoundManagerMessageProbe.java "$WORK/message.bin" \
    >"$WORK/usound.log" 2>&1 || {
      cat "$WORK/usound.log" >&2
      exit 1
    }

for marker in \
  'USOUND STATUS MESSAGE PASS input=0x00000000 activeFlag=0 message=01 02 00 00 00 11 00 00' \
  'USOUND STATUS MESSAGE PASS input=0x00010000 activeFlag=0 message=01 02 00 00 00 10 00 00' \
  'USOUND STATUS MESSAGE PASS input=0x00000003 activeFlag=0 message=01 02 00 00 03 0e 00 00' \
  'USOUND STATUS MESSAGE PASS input=0x00000001 activeFlag=0 message=01 02 00 00 09 0f 00 00' \
  'USOUND STATUS MESSAGE PASS input=0x00000000 activeFlag=1 message=01 02 00 00 0a 4f 00 00' \
  'USOUND STATUS MESSAGE BRANCH MATRIX PASS'; do
  if ! grep -Fq "$marker" "$WORK/usound.log"; then
    echo "missing probe marker: $marker" >&2
    cat "$WORK/usound.log" >&2
    exit 1
  fi
done
JAVA_HOME_OVERRIDE="$JAVA_HOME_OVERRIDE" \
GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/manager-project" m30manager201 \
    -import "$MANAGER_AP" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$MANAGER_AP" \
    -postScript RunManager201ReceiverProbe.java "$WORK/message.bin" \
    >"$WORK/manager.log" 2>&1 || {
      cat "$WORK/manager.log" >&2
      exit 1
    }
grep -E 'USOUND STATUS MESSAGE (PASS|BRANCH MATRIX PASS)' "$WORK/usound.log"
grep 'MANAGER 0x201 EQUAL-STATE BRANCH PASS' "$WORK/manager.log"
echo 'USOUND STATUS ROUTE PROBE PASS scope=real-callback+sender+manager-handler synthetic-state+resident-services+transport'
