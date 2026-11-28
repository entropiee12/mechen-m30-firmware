#!/usr/bin/env bash
# Trace the card-driver export vector and the SD controller's detect helpers.
# Static Ghidra analysis only; no device or SD-card access.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
CARD=${1:-$ROOT/analysis/community/fw/card.drv}
CARDS=${2:-$ROOT/analysis/community/fw/cards.drv}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}

if [ -z "${JAVA_HOME:-}" ] && command -v brew >/dev/null 2>&1; then
  JAVA_PREFIX=$(brew --prefix openjdk@25 2>/dev/null || true)
  JAVA_HOME="$JAVA_PREFIX/libexec/openjdk.jdk/Contents/Home"
fi
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  export JAVA_HOME
fi

[ -f "$CARD" ] || { echo "missing card driver: $CARD" >&2; exit 1; }
[ -f "$CARDS" ] || { echo "missing cards driver: $CARDS" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

CARD_SHA=$(shasum -a 256 "$CARD" | awk '{print $1}')
CARDS_SHA=$(shasum -a 256 "$CARDS" | awk '{print $1}')
[ "$CARD_SHA" = fc355c17258fe6fca184e5a616ec4c0b151a566caa99feba9b14981a046063bf ] || {
  echo "unexpected card.drv SHA-256: $CARD_SHA" >&2; exit 1;
}
[ "$CARDS_SHA" = 3c7fb89cf2e8e2f8bdb2311b386e7047f17c35b5846e71139a105255b88462c1 ] || {
  echo "unexpected cards.drv SHA-256: $CARDS_SHA" >&2; exit 1;
}

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-ghidra-card-trigger.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project" "$WORK/home"

# card.drv's last YQHX segment is a six-entry function-pointer vector at
# 0x9fc19500. MapSegments uses BASE + FILE_OFFSET for the destination.
set +e
JAVA_TOOL_OPTIONS="-Duser.home=$WORK/home" \
  "$GHIDRA" "$WORK/project" m30cardtrigger \
  -import "$CARD" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript MapSegments.java 3 \
    "$CARD" 0xbfc17200 0x200 0x9d0 1 \
    "$CARD" 0xbfc27400 0xc00 0xa10 1 \
    "$CARD" 0x9fc17e00 0x1700 0x44 0 \
  -postScript DumpWords.java 0x9fc19500 0x9fc19518 \
  -postScript DumpAt.java \
    0xbfc179ec,0xbfc17b1c,0xbfc17b34,0xbfc17b4c,0xbfc17be8,0xbfc17bf4,0xbfc17bf8 \
    "$WORK/card.c" 2>&1 | tee "$WORK/card.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"
for word in \
  'address=9fc19500 value=0xbfc17b1d' \
  'address=9fc19504 value=0xbfc17b35' \
  'address=9fc19508 value=0xbfc17b4d' \
  'address=9fc1950c value=0xbfc17be9' \
  'address=9fc19510 value=0xbfc17bf5' \
  'address=9fc19514 value=0xbfc17bf9'; do
  grep -q "$word" "$WORK/card.log"
done
for address in bfc179ec bfc17b1c bfc17b34 bfc17b4c bfc17be8 bfc17bf4 bfc17bf8; do
  grep -q "FUN_$address" "$WORK/card.c"
done

# The controller entry is the only code root for the two low-level probes in
# the mapped cards.drv image; retain the emitted xrefs as the bounded result.
set +e
JAVA_TOOL_OPTIONS="-Duser.home=$WORK/home" \
  "$GHIDRA" "$WORK/project" m30cardtrigger \
  -import "$CARDS" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript FlatLoad.java "$CARDS" 0xbfc27e00 0x200 0x1400 \
  -postScript DumpRefsTo.java 0xbfc28d60,0xbfc290d8,0xbfc29200 \
  -postScript DumpAt.java 0xbfc28d60,0xbfc290d8,0xbfc29200 "$WORK/cards.c" \
  2>&1 | tee "$WORK/cards.log"
STATUS=${PIPESTATUS[0]}
set -e
[ "$STATUS" -eq 0 ] || exit "$STATUS"
grep -q 'REF target=bfc290d8 from=bfc28ea0 type=UNCONDITIONAL_CALL function=FUN_bfc28d60' "$WORK/cards.log"
grep -q 'REF target=bfc29200 from=bfc28e08 type=UNCONDITIONAL_CALL function=FUN_bfc28d60' "$WORK/cards.log"
grep -A1 'REF TARGET bfc28d60' "$WORK/cards.log" | grep -q 'REF none'
[ "$(grep -c 'REF target=bfc290d8' "$WORK/cards.log")" -eq 1 ]
[ "$(grep -c 'REF target=bfc29200' "$WORK/cards.log")" -eq 1 ]
for address in bfc28d60 bfc290d8 bfc29200; do
  grep -q "FUN_$address" "$WORK/cards.c"
done

echo "CARD TRIGGER BOUNDARY PASS card_sha256=$CARD_SHA cards_sha256=$CARDS_SHA export_slots=6 detect_helpers=entry-only"
