#!/usr/bin/env bash
# Bounded static scan of the five overlays that read or save the settings block.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SOURCE="$ROOT/analysis/community/fw"
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
OUTDIR=${1:-$(mktemp -d "${TMPDIR:-/private/tmp}/m30-persistent-dae-writers.XXXXXX")}

if [ ! -x "$GHIDRA" ]; then
  echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2
  exit 1
fi

mkdir -p "$OUTDIR"
echo "Probe output: $OUTDIR"

while read -r name expected addresses; do
  ap="$SOURCE/$name.ap"
  actual=$(shasum -a 256 "$ap" | awk '{print $1}')

  if [ "$actual" != "$expected" ]; then
    echo "SHA-256 mismatch for $ap: $actual" >&2
    exit 1
  fi

  echo "VERIFIED input $name.ap sha256=$actual"
  mkdir -p "$OUTDIR/$name/project" "$OUTDIR/$name/home"

  if ! GHIDRA_JAVA_OPTIONS="-Duser.home=$OUTDIR/$name/home" \
    "$GHIDRA" "$OUTDIR/$name/project" "persistent_$name" \
      -import "$ap" \
      -loader BinaryLoader \
      -processor 'MIPS:LE:32:default' \
      -noanalysis \
      -scriptPath "$ROOT/tools/ghidra" \
      -postScript LoadAP.java "$ap" \
      -postScript DumpAt.java "$addresses" "$OUTDIR/$name/functions.c" \
      -postScript ScanPersistentDaeWriters.java \
      >"$OUTDIR/$name/ghidra.log" 2>&1; then
    tail -80 "$OUTDIR/$name/ghidra.log" >&2
    exit 1
  fi

  if rg -q 'ERROR REPORT SCRIPT ERROR|DumpAt: no function|// failed:' "$OUTDIR/$name/ghidra.log" "$OUTDIR/$name/functions.c"; then
    echo "Ghidra failed to decompile a requested function in $name.ap" >&2
    exit 1
  fi

  if ! rg -q 'WRITER_SCAN_TOTAL functions=[0-9]+ failures=0 candidates=[0-9]+ cap=1200' "$OUTDIR/$name/ghidra.log"; then
    echo "bounded writer scan did not complete cleanly for $name.ap" >&2
    exit 1
  fi

  rg 'DumpAt:|WRITER_SCAN_TOTAL' "$OUTDIR/$name/ghidra.log"
  rg 'WRITER_CANDIDATE.*pool=.*(0x9fc15|0x000f0000)' "$OUTDIR/$name/ghidra.log" || true
done <<'INPUTS'
config 5b5687c00880e691500c7abbfba43dab51ae0f95d2203f74aced2e8d56357580 0x40054ccc,0x40054f38,0x40054f84,0x40dd4c00,0x40dd4fe8
mengine b569605e9f66ef94023cf82d665ae119f6bbb7ed0c9d8f5e6c5be867ce91be49 0x60050d00,0x601d0d58
music 662e379602707a9c4b0dcd6b94b591dd77c2b5f7a557fcb03b8c2a5c9b1baac3 0x40054ce8,0x40294dc0,0x40094d4c
setting ad46a92fc0b12f58fdad3a7885502ef5e7481c7b2913fd7e28d46216d22a9ed7 0x40055200,0x402550b4
mainmenu 1ef9b7a466a0f3a6bec8ce8654422c372fd68105ff94739077bfe34b435a1105 0x40014d74,0x40014fac,0x40154cec,0x40154f44
INPUTS

echo "PERSISTENT DAE WRITERS STATIC SCAN PASS output=$OUTDIR"
