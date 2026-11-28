#!/usr/bin/env bash
# Execute mmm_pp attach and its registered SHM callback with synthetic services.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AL=${1:-$ROOT/analysis/community/fw/mmm_pp.al}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
EXPECTED_SHA256=9a7a6c2215b2594e2ef816b8a3e592968921afff7747f3cadbd6e71bf74047d6

[ -f "$AL" ] || { echo "missing mmm_pp.al: $AL" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
actual_sha256=$(shasum -a 256 "$AL" | awk '{print $1}')
[ "$actual_sha256" = "$EXPECTED_SHA256" ] || {
  echo "mmm_pp.al SHA-256 $actual_sha256 != $EXPECTED_SHA256" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-mmm-pp-attach.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

if ! GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" mmmppattach \
    -import "$AL" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$AL" \
    -postScript RunMmmPpAttachProbe.java "$AL" \
    >"$WORK/run.log" 2>&1; then
  cat "$WORK/run.log" >&2
  exit 1
fi

if ! grep -Fq 'MMM_PP ATTACH P-CODE PASS ' "$WORK/run.log"; then
  cat "$WORK/run.log" >&2
  echo "mmm_pp attach probe did not report PASS" >&2
  exit 1
fi

grep -E 'MMM_PP ATTACH (SERVICE|P-CODE PASS)' "$WORK/run.log"
