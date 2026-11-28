#!/usr/bin/env bash
# Execute FUN_40094e08 through the real fsel dispatcher and directory callback.
# Only resident filesystem/list/ROM services are synthetic.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:-$ROOT/analysis/community/fw/browser.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
COMMUNITY_SHA256=10b0e5d8ed725a76ebce0bfa6e843427115d55b59216fd461a22703e3fed34b3
STOCK_SHA256=b6e25204d0844b3731292016936b327d70c958e8f2ba46c59216c7633eb8f2f4

[ -f "$AP" ] || { echo "missing browser AP: $AP" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
ACTUAL=$(shasum -a 256 "$AP" | awk '{print $1}')
case "$ACTUAL" in
  "$COMMUNITY_SHA256"|"$STOCK_SHA256") ;;
  *) echo "unexpected browser.ap SHA-256: $ACTUAL" >&2; exit 1 ;;
esac

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-browser-count-consumer.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project" "$WORK/home"

if ! GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home}" \
  "$GHIDRA" "$WORK/project" m30browserListCountConsumer \
    -import "$AP" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$AP" \
    -postScript RunBrowserListCountConsumerProbe.java \
    >"$WORK/run.log" 2>&1; then
  cat "$WORK/run.log" >&2
  exit 1
fi

if ! grep -Fq 'BROWSER LIST COUNT CONSUMER PASS' "$WORK/run.log"; then
  cat "$WORK/run.log" >&2
  echo "Ghidra completed without the expected list-count consumer result" >&2
  exit 1
fi

grep -F 'BROWSER LIST COUNT CONSUMER PASS' "$WORK/run.log"
