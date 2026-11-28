#!/usr/bin/env bash
# Compare the real MIPS16 ADC classifier against the evidence-backed host model.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/key.drv}
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
PY=${PYTHON:-python3}

if [ -z "${JAVA_HOME:-}" ] && command -v brew >/dev/null 2>&1; then
  JAVA_PREFIX=$(brew --prefix openjdk@25 2>/dev/null || true)
  JAVA_HOME="$JAVA_PREFIX/libexec/openjdk.jdk/Contents/Home"
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  export JAVA_HOME
fi

[ -f "$DRIVER" ] || { echo "missing key driver: $DRIVER" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
"$PY" "$ROOT/tools/emu/key_binary_probe.py" "$DRIVER"

WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-key-classifier.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project" "$WORK/home"

if ! JAVA_TOOL_OPTIONS="-Duser.home=$WORK/home" "$GHIDRA" \
  "$WORK/project" m30-key-classifier \
  -import "$DRIVER" \
  -loader BinaryLoader \
  -processor 'MIPS:LE:32:default' \
  -noanalysis \
  -scriptPath "$ROOT/tools/ghidra" \
  -postScript FlatLoad.java "$DRIVER" 0xbfc0fe00 0x200 0xa00 \
  -postScript RunKeyClassifierMatrix.java \
  >"$WORK/ghidra.log" 2>&1; then
  cat "$WORK/ghidra.log"
  exit 1
fi

"$PY" - "$WORK/ghidra.log" "$ROOT" <<'PY'
import re
import sys
from pathlib import Path

root = Path(sys.argv[2])
sys.path.insert(0, str(root / "tools/sim"))
from key_driver import classify_adc_sample

log = Path(sys.argv[1]).read_text(errors="replace")
observed = {
    (int(sample, 16), int(override)): int(result, 16)
    for sample, override, result in re.findall(
        r"CASE sample=([0-9a-f]{2}) override=([01]) result=([0-9a-f]{2})",
        log,
    )
}
if len(observed) != 512:
    print(log)
    raise SystemExit(f"expected 512 classifier cases, got {len(observed)}")

normalized_values = (0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77)
mismatches = []
for override in (0, 1):
    for sample in range(256):
        expected = classify_adc_sample(
            sample,
            normalized_values,
            override=bool(override),
            override_value=0x7e,
        ).normalized_value
        actual = observed[(sample, override)]
        if actual != expected:
            mismatches.append((sample, override, expected, actual))

if mismatches:
    for sample, override, expected, actual in mismatches[:16]:
        print(
            f"MISMATCH sample=0x{sample:02x} override={override} "
            f"host=0x{expected:02x} mips=0x{actual:02x}"
        )
    raise SystemExit(f"classifier matrix failed: {len(mismatches)} mismatches")

print("KEY CLASSIFIER HOST/MIPS PASS cases=512 thresholds=7 override-register-bits=0,1")
PY
