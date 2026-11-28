#!/usr/bin/env bash
# Recover code references to browser count and callback literal-table entries.
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

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-browser-count-xrefs.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project" "$WORK/home" "$WORK/tmp"

if ! GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$WORK/home -Djava.io.tmpdir=$WORK/tmp}" \
  "$GHIDRA" "$WORK/project" m30browserCountXrefs \
    -import "$AP" \
    -loader BinaryLoader \
    -processor 'MIPS:LE:32:default' \
    -noanalysis \
    -scriptPath "$ROOT/tools/ghidra" \
    -postScript LoadAP.java "$AP" \
    -postScript DumpRefsTo.java 0x40094f84,0x40cd5204,0x40cd52d0,0x40cd52d4,0x40cd52d8,0x40cd52dc,0x40d15254,0x48754930,0x48754934,0x48754938,0x48754a3c \
    -postScript DumpAt.java 0x40d14f14 "$WORK/event_handler.c" \
    -postScript DumpAt.java 0x4875493c "$WORK/callback.c" \
    >"$WORK/run.log" 2>&1; then
  cat "$WORK/run.log" >&2
  exit 1
fi

for marker in \
  'REF target=40094f84 from=40094e10 type=READ function=FUN_40094e08' \
  'REF target=40cd52d0 from=40cd523a type=READ function=FUN_40cd5218' \
  'REF target=40cd52dc from=40cd52c0 type=READ function=FUN_40cd5218' \
  'REF target=40d15254 from=40d15172 type=READ function=FUN_40d14f14' \
  'REF target=48754938 from=48754874 type=READ function=FUN_487546d8' \
  'REF target=48754a3c from=4875494a type=READ function=FUN_4875493c' \
  'DumpAt: 40d14f14 -> FUN_40d14f14' \
  'DumpAt: 4875493c -> FUN_4875493c'; do
  grep -Fq "$marker" "$WORK/run.log" || {
    echo "missing xref/decompile marker: $marker" >&2
    cat "$WORK/run.log" >&2
    exit 1
  }
done

for marker in \
  'case 0x2704:' \
  'case 0x2706:' \
  'param_2 + 0xe'; do
  grep -Fq "$marker" "$WORK/event_handler.c" || {
    echo "missing decompile marker: $marker" >&2
    cat "$WORK/event_handler.c" >&2
    exit 1
  }
done
if ! python3 -c 'import pathlib,re,sys; s=pathlib.Path(sys.argv[1]).read_text(); a=re.search(r"case 0x2704:(.*?)case 0x2705:",s,re.S); b=re.search(r"case 0x2706:(.*?)default:",s,re.S); p=r"\*\((?:u?short) \*\)\((?:DAT|_UNK)_40d15254 \+ 2\)"; sys.exit(0 if a and b and re.search(p,a.group(1)) and re.search(p,b.group(1)) else 1)' "$WORK/event_handler.c"; then
  echo 'event handler count reads not found inside both cases' >&2
  cat "$WORK/event_handler.c" >&2
  exit 1
fi
if ! python3 -c 'import pathlib,re,sys; s=pathlib.Path(sys.argv[1]).read_text(); p=r"sVar1\s*=\s*\(\*\*\(code \*\*\)\(\*\(int \*\)\(param_2 \+ 4\) \+ 4\)\)\s*\(\s*param_1,\s*\*\(short \*\)\(param_2 \+ 0xe\)\s*-\s*\*\(short \*\)\((?:DAT|_UNK)_48754a3c \+ 2\),\s*param_3,\s*param_4,0\);"; sys.exit(0 if re.search(p,s) else 1)' "$WORK/callback.c"; then
  echo 'callback decompile does not show the count-relative index passed to the event callback' >&2
  cat "$WORK/callback.c" >&2
  exit 1
fi
if grep -Eq '\*\([^)]*\)\((DAT|_UNK)_40d15254 \+ 2\)[[:space:]]*=' "$WORK/event_handler.c"; then
  echo 'event handler decompile writes to the count halfword' >&2
  cat "$WORK/event_handler.c" >&2
  exit 1
fi
if grep -Eq '\*\([^)]*\)\((DAT|_UNK)_48754a3c \+ 2\)[[:space:]]*=' "$WORK/callback.c"; then
  echo 'callback decompile writes to the count halfword' >&2
  cat "$WORK/callback.c" >&2
  exit 1
fi

grep -E 'REF TARGET|REF target=' "$WORK/run.log"
