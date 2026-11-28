#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:-$ROOT/analysis/community/fw/playlist.ap}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}
EXPECTED_SHA256=184f2c201537e6657024a2a5a054d12fe4a7f134e06205f0786a8d71d2a29403

[ -f "$AP" ] || { echo "missing playlist.ap: $AP" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
[ "$(shasum -a 256 "$AP" | awk '{print $1}')" = "$EXPECTED_SHA256" ] || {
  echo "playlist.ap hash mismatch" >&2; exit 1;
}

WORK=$(mktemp -d "${M30_TMPDIR:-/private/tmp}/m30-playlist-gate.XXXXXX")
trap 'if [ "${M30_KEEP_WORK:-0}" != 1 ]; then rm -rf "$WORK"; fi' EXIT
mkdir -p "$WORK/home" "$WORK/project"

JAVA_TOOL_OPTIONS="-Duser.home=$WORK/home" JAVA_HOME="$JAVA_HOME_OVERRIDE" \
  "$GHIDRA" "$WORK/project" playlistgates \
    -import "$AP" -loader BinaryLoader -processor 'MIPS:LE:32:default' -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$AP" \
    -postScript RunPlaylistParamGateProbe.java "$AP" \
    >"$WORK/run.log" 2>&1 || { cat "$WORK/run.log" >&2; exit 1; }

for expected in \
  'PLAYLIST PARAM P-CODE CASE param=0x42 op9=0 m3u-callbacks=1 music-callbacks=1 events=[resident-op9, create-file, MUSIC.LIB, finalize-file, create-file, M3U.LIB, finalize-file]' \
  'PLAYLIST PARAM P-CODE CASE param=0x82 op9=not-called m3u-callbacks=0 music-callbacks=0 events=[]' \
  'PLAYLIST PARAM P-CODE CASE param=0xc2 op9=0 m3u-callbacks=1 music-callbacks=1 events=[resident-op9, create-file, MUSIC.LIB, finalize-file, create-file, M3U.LIB, finalize-file]' \
  'PLAYLIST PARAM P-CODE CASE param=0x4e op9=0 m3u-callbacks=1 music-callbacks=1 events=[resident-op9, create-file, MUSIC.LIB, finalize-file, create-file, M3U.LIB, finalize-file]' \
  'PLAYLIST PARAM P-CODE CASE param=0x42 op9=-1 m3u-callbacks=0 music-callbacks=0 events=[resident-op9]' \
  'PLAYLIST PARAM P-CODE PASS service=resident-op9-0xbfc16bb1 semantics=synthetic-return callback=synthetic-indexer'; do
  grep -Fq "$expected" "$WORK/run.log" || { cat "$WORK/run.log" >&2; exit 1; }
done
grep 'PLAYLIST PARAM P-CODE' "$WORK/run.log"
if [ "${M30_KEEP_WORK:-0}" = 1 ]; then echo "PRESERVED_WORK=$WORK"; fi
