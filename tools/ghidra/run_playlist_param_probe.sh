#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
FW=${1:-$ROOT/analysis/community/fw}
PLAYLIST="$FW/playlist.ap"
MANAGER="$FW/manager.ap"
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
JAVA_HOME_OVERRIDE=${JAVA_HOME_OVERRIDE:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}
PLAYLIST_SHA=184f2c201537e6657024a2a5a054d12fe4a7f134e06205f0786a8d71d2a29403
MANAGER_SHA=d94d2406d5e88efee1ffacff2d399ce7d865410ac7458efdb9f185eae359af7a

for item in "$PLAYLIST" "$MANAGER"; do
  [ -f "$item" ] || { echo "missing AP: $item" >&2; exit 1; }
done
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
[ "$(shasum -a 256 "$PLAYLIST" | awk '{print $1}')" = "$PLAYLIST_SHA" ] || {
  echo "playlist.ap hash mismatch" >&2; exit 1;
}
[ "$(shasum -a 256 "$MANAGER" | awk '{print $1}')" = "$MANAGER_SHA" ] || {
  echo "manager.ap hash mismatch" >&2; exit 1;
}

WORK=$(mktemp -d "${M30_TMPDIR:-/private/tmp}/m30-playlist-param.XXXXXX")
trap 'if [ "${M30_KEEP_WORK:-0}" != 1 ]; then rm -rf "$WORK"; fi' EXIT
mkdir -p "$WORK/home" "$WORK/playlist-project" "$WORK/manager-project"

JAVA_TOOL_OPTIONS="-Duser.home=$WORK/home" JAVA_HOME="$JAVA_HOME_OVERRIDE" \
  "$GHIDRA" "$WORK/playlist-project" playlistparam \
    -import "$PLAYLIST" -loader BinaryLoader -processor 'MIPS:LE:32:default' -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$PLAYLIST" \
    -postScript DumpAt.java '0x40054c54,0x40055194,0x400551d0' "$WORK/playlist-functions.c" \
    -postScript DumpWords.java 0x40054fec 0x40055008 \
    -postScript DumpWords.java 0x40055204 0x4005520c \
    >"$WORK/playlist.log" 2>&1 || { cat "$WORK/playlist.log" >&2; exit 1; }

JAVA_TOOL_OPTIONS="-Duser.home=$WORK/home" JAVA_HOME="$JAVA_HOME_OVERRIDE" \
  "$GHIDRA" "$WORK/manager-project" managerparam \
    -import "$MANAGER" -loader BinaryLoader -processor 'MIPS:LE:32:default' -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$MANAGER" \
    -postScript DumpAt.java 0x7f196200 "$WORK/manager-functions.c" \
    >"$WORK/manager.log" 2>&1 || { cat "$WORK/manager.log" >&2; exit 1; }

grep -Fq 'param_1 & 0x40' "$WORK/playlist-functions.c"
grep -Fq 'FUN_40055194 @ 40055194' "$WORK/playlist-functions.c"
grep -Fq 'FUN_400551d0 @ 400551d0' "$WORK/playlist-functions.c"
grep -Fq 'WORD address=40055204 value=0x40055195' "$WORK/playlist.log"
grep -Fq 'WORD address=40055208 value=0x400551d1' "$WORK/playlist.log"
grep -Fq 'uStack_10 = *(undefined1 *)((int)param_1 + 5);' "$WORK/manager-functions.c"

echo "PLAYLIST PARAM PROBE PASS playlist=$PLAYLIST_SHA manager=$MANAGER_SHA"
rg 'WORD address=4005520[48]' "$WORK/playlist.log"
rg 'DECOMPILE FUN_|DumpAt:' "$WORK/playlist.log" "$WORK/manager.log"
if [ "${M30_KEEP_WORK:-0}" = 1 ]; then echo "PRESERVED_WORK=$WORK"; fi
