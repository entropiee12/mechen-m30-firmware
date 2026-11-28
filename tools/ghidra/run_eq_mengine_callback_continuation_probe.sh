#!/usr/bin/env bash
# Invoke MEngine's registered callback under an explicit synthetic scheduler hypothesis.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:-$ROOT/analysis/community/fw/mengine.ap}
OP5_RESULT=${2:-0}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
STOCK_SHA256=1340bac3cb68043f7a9651ee7959c2f502e6759c7debe0626f07ef9231896d4d
COMMUNITY_SHA256=b569605e9f66ef94023cf82d665ae119f6bbb7ed0c9d8f5e6c5be867ce91be49

[ -f "$AP" ] || { echo "missing MEngine AP: $AP" >&2; exit 1; }
case "$OP5_RESULT" in 0|1) ;; *) echo "OP5_RESULT must be 0 or 1" >&2; exit 1 ;; esac
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
actual_sha256=$(shasum -a 256 "$AP" | awk '{print $1}')
case "$actual_sha256" in
  "$STOCK_SHA256") variant=stock-v1.101.10 ;;
  "$COMMUNITY_SHA256") variant=community-v1.292.00 ;;
  *) echo "unsupported MEngine SHA-256: $actual_sha256" >&2; exit 1 ;;
esac

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-eq-callback-continuation.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30eqcallbackcontinuation \
    -import "$AP" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$AP" \
    -postScript RunEqMengineCallbackContinuationProbe.java "$OP5_RESULT" \
    >"$WORK/run.log" 2>&1 || {
      cat "$WORK/run.log" >&2
      exit 1
    }

if ! grep -Fq 'EQ MENGINE CALLBACK CONTINUATION PASS ' "$WORK/run.log"; then
  cat "$WORK/run.log" >&2
  echo "MEngine callback-continuation probe did not report PASS" >&2
  exit 1
fi
grep -E 'EQ MENGINE CALLBACK (CONTINUATION PASS|BOUNDARY|AFTER SYNTHETIC RESULT)' "$WORK/run.log"
echo "EQ MENGINE CALLBACK CONTINUATION CORPUS PASS variant=$variant sha256=$actual_sha256 op5-result=$OP5_RESULT invocation=scheduler-hypothesis"
