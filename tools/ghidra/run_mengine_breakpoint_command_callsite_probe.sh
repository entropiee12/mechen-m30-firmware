#!/usr/bin/env bash
# Check the bounded MEngine caller set for the mmm_mp breakpoint command bridge.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
STOCK=${1:?usage: run_mengine_breakpoint_command_callsite_probe.sh STOCK_MENGINE.AP COMMUNITY_MENGINE.AP}
COMMUNITY=${2:?usage: run_mengine_breakpoint_command_callsite_probe.sh STOCK_MENGINE.AP COMMUNITY_MENGINE.AP}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}

verify_hash() {
  local path=$1 expected=$2 actual
  [ -f "$path" ] || { echo "missing MEngine AP: $path" >&2; exit 1; }
  actual=$(shasum -a 256 "$path" | awk '{print $1}')
  [ "$actual" = "$expected" ] || {
    echo "unexpected SHA-256 for $path: $actual" >&2
    exit 1
  }
}

verify_hash "$STOCK" 1340bac3cb68043f7a9651ee7959c2f502e6759c7debe0626f07ef9231896d4d
verify_hash "$COMMUNITY" b569605e9f66ef94023cf82d665ae119f6bbb7ed0c9d8f5e6c5be867ce91be49
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-mengine-breakpoint-calls.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

run_one() {
  local path=$1 label=$2
  mkdir -p "$WORK/$label/project" "$WORK/$label/home" "$WORK/$label/tmp"
  if ! GHIDRA_JAVA_OPTIONS="-Duser.home=$WORK/$label/home -Djava.io.tmpdir=$WORK/$label/tmp" \
    "$GHIDRA" "$WORK/$label/project" "$label" \
      -import "$path" \
      -loader BinaryLoader \
      -processor 'MIPS:LE:32:default' \
      -noanalysis \
      -scriptPath "$ROOT/tools/ghidra" \
      -postScript LoadAP.java "$path" \
      -postScript DumpAt.java 0x60190eec "$WORK/$label/function.c" \
      -postScript DumpRefsTo.java 0x6019108c \
      >"$WORK/$label/run.log" 2>&1; then
    cat "$WORK/$label/run.log" >&2
    return 1
  fi

  python3 - "$WORK/$label/function.c" "$WORK/$label/run.log" "$label" <<'PY'
import pathlib
import re
import sys

function_path, log_path, label = map(pathlib.Path, sys.argv[1:])
function = function_path.read_text()
log = log_path.read_text()
refs = re.findall(
    r"REF target=6019108c from=([0-9a-f]+) type=([A-Z]+) function=([^\s]+)",
    log,
    re.IGNORECASE,
)
if not refs:
    raise SystemExit(f"{label}: no references to MMP command bridge literal")
unexpected = [row for row in refs if row[2] != "FUN_60190eec"]
if unexpected:
    raise SystemExit(f"{label}: unexpected command-bridge callers: {unexpected}")
if "FUN_60190eec" not in function or "0x15" not in function:
    raise SystemExit(f"{label}: breakpoint handler or command 0x15 missing")
if re.search(r"\(\*_UNK_6019108c\)\([^\n]*0x16", function):
    raise SystemExit(f"{label}: command 0x16 call found in breakpoint handler")
print(f"{label}: bridge refs={len(refs)} caller=FUN_60190eec command15=yes command16=no")
PY
}

run_one "$STOCK" stock
run_one "$COMMUNITY" community
echo 'MENGINE BREAKPOINT COMMAND CALLSITE PASS scope=0x6019108c'
