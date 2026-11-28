#!/usr/bin/env bash
# Trace the UDisk AP's use of the fixed address 0xbfc002a0.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
if [ "$#" -ne 2 ]; then
  echo "usage: $0 <community-udisk.ap> <stock-udisk.ap>" >&2
  exit 2
fi
COMMUNITY_AP=$1
STOCK_AP=$2
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
COMMUNITY_SHA256=a7c5a1fe9b73601bfad849fc71cff7f5c919b39f740e92d5e1864435f0c1cae4
STOCK_SHA256=0cc4c679d956f56e29d9fd3f9d5fe53e2134b072db07eba8e542468d63a56548

[ -f "$COMMUNITY_AP" ] || { echo "missing community udisk.ap: $COMMUNITY_AP" >&2; exit 1; }
[ -f "$STOCK_AP" ] || { echo "missing stock udisk.ap: $STOCK_AP" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
community_sha=$(shasum -a 256 "$COMMUNITY_AP" | awk '{print $1}')
stock_sha=$(shasum -a 256 "$STOCK_AP" | awk '{print $1}')
[ "$community_sha" = "$COMMUNITY_SHA256" ] || {
  echo "community udisk.ap SHA-256 mismatch: $community_sha" >&2; exit 1;
}
[ "$stock_sha" = "$STOCK_SHA256" ] || {
  echo "stock udisk.ap SHA-256 mismatch: $stock_sha" >&2; exit 1;
}
python3 -c 'import pathlib,sys; c=pathlib.Path(sys.argv[1]).read_bytes(); s=pathlib.Path(sys.argv[2]).read_bytes(); assert c[0xfcc:0x1050] == s[0xfcc:0x1050], "callsite/literal slice differs"; assert c[0x102c:0x1030] == bytes.fromhex("a0 02 c0 bf"), "unexpected fixed-address literal"; print("NORMBOOT UDISK CROSS-VARIANT SLICE PASS file=0xfcc..0x1050 literal=0xbfc002a0")' "$COMMUNITY_AP" "$STOCK_AP"

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-normboot-udisk.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

run_case() {
  local label=$1 ap=$2
  local project="$WORK/$label/project" home="$WORK/$label/home"
  local log="$WORK/$label.log" decomp="$WORK/$label.c"
  mkdir -p "$project" "$home"
  GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$home -Djava.io.tmpdir=$WORK}" \
    "$GHIDRA" "$project" "m30normbootudisk-$label" \
      -import "$ap" \
      -loader BinaryLoader \
      -processor 'MIPS:LE:32:default' \
      -noanalysis \
      -scriptPath "$ROOT/tools/ghidra" \
      -postScript LoadAP.java "$ap" \
      -postScript DumpWords.java 0x40014f28 0x40014f4c \
      -postScript DumpAt.java 0x40014ecc "$decomp" containing \
      -postScript DumpRefsTo.java 0x40014f2c \
      >"$log" 2>&1 || {
        cat "$log" >&2
        exit 1
      }

  for marker in \
    'LoadAP: blocks=13 prologue functions=95 entry=40014c30' \
    'WORD address=40014f28 value=0x9fc1564c' \
    'WORD address=40014f2c value=0xbfc002a0' \
    'WORD address=40014f30 value=0xbfc1640d' \
    'WORD address=40014f38 value=0xbfc16401' \
    'DumpAt: 40014ecc -> FUN_40014ecc @ 40014ecc' \
    'REF target=40014f2c from=40014ed2 type=READ function=FUN_40014ecc'; do
    grep -Fq "$marker" "$log" || {
      cat "$log" >&2
      exit 1
    }
  done
  grep -Fq '*_UNK_40014f28 = *_UNK_40014f2c;' "$decomp"
  grep -Fq '(*(code *)*_UNK_40014f28)();' "$decomp"
  rg 'WORD address=40014f(2c|30|38)|DumpAt:|REF target=40014f2c' "$log"
  rg -F '(*_UNK_40014f30)(10,7,0,5);' "$decomp"
  rg -F '(*_UNK_40014f38)(3,0,0,0x1d);' "$decomp"
  rg -F '(*(code *)*_UNK_40014f28)();' "$decomp"
  echo "NORMBOOT UDISK GHIDRA CASE PASS variant=$label"
}

run_case community "$COMMUNITY_AP"
run_case stock "$STOCK_AP"
echo "NORMBOOT UDISK POINTER PASS community_sha256=$community_sha stock_sha256=$stock_sha global=0x9fc1564c direct-call-context-confirmed"
