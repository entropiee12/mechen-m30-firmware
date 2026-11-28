#!/usr/bin/env bash
# Decompile the hash-pinned music.ap 0x861c app-return route.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:-$ROOT/analysis/community/fw/music.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}
EXPECTED_SHA256=662e379602707a9c4b0dcd6b94b591dd77c2b5f7a557fcb03b8c2a5c9b1baac3

[ -f "$AP" ] || { echo "missing community music.ap: $AP" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
actual_sha256=$(shasum -a 256 "$AP" | awk '{print $1}')
[ "$actual_sha256" = "$EXPECTED_SHA256" ] || {
  echo "music.ap SHA-256 $actual_sha256 != $EXPECTED_SHA256" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-music-index-return.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/home" "$WORK/project"

if ! JAVA_HOME="$JAVA_HOME_OVERRIDE" \
  JAVA_TOOL_OPTIONS="-Duser.home=$WORK/home" \
  "$GHIDRA" "$WORK/project" musicindexreturn \
    -import "$AP" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$AP" \
    -postScript FindImmediateAndDecompile.java 0 \
      0x40054ff4 0x40054d88 0x400552a0 0x40614fc0 \
    -postScript DumpWords.java 0x40054f40 0x40054f68 \
    -postScript DumpWords.java 0x40055278 0x400552a0 \
    -postScript DumpWords.java 0x40615140 0x40615160 \
    >"$WORK/run.log" 2>&1; then
  cat "$WORK/run.log" >&2
  exit 1
fi

for function in 40054ff4 40054d88 400552a0 40614fc0; do
  if ! grep -Fq "DECOMPILE FUN_$function @ $function" "$WORK/run.log"; then
    cat "$WORK/run.log" >&2
    echo "missing decompile for FUN_$function" >&2
    exit 1
  fi
done

if ! grep -Fq 'WORD address=40615148 value=0x9fc15584' "$WORK/run.log"; then
  cat "$WORK/run.log" >&2
  echo "missing confirmed return-state pointer at 0x40615148" >&2
  exit 1
fi

cat "$WORK/run.log"
