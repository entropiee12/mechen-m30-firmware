#!/usr/bin/env bash
# Verify uses of resident entry 0xbfc164dc across stock/community music,
# usound, and udisk APs. This does not emulate the resident ROM body.
set -euo pipefail

if [ "$#" -ne 6 ]; then
  echo "usage: $0 <community-music.ap> <community-usound.ap> <community-udisk.ap> <stock-music.ap> <stock-usound.ap> <stock-udisk.ap>" >&2
  exit 2
fi

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-resident-service-xrefs.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }
mkdir -p "$WORK/scripts"
cp "$ROOT/tools/ghidra/LoadAP.java" \
  "$ROOT/tools/ghidra/DumpRefsTo.java" \
  "$ROOT/tools/ghidra/FindPointerRefs.java" \
  "$ROOT/tools/ghidra/DumpAt.java" "$WORK/scripts/"

expect_log() {
  local marker=$1 log=$2
  grep -Fq "$marker" "$log" || { cat "$log" >&2; exit 1; }
}

run_case() {
  local variant=$1 module=$2 ap=$3 expected_sha=$4
  local project="$WORK/$variant-$module/project" home="$WORK/$variant-$module/home"
  local log="$WORK/$variant-$module.log" output="$WORK/$variant-$module.c"
  local actual_sha call_count expected_count dump_address
  local -a expected_refs expected_pointers
  actual_sha=$(shasum -a 256 "$ap" | awk '{print $1}')
  [ "$actual_sha" = "$expected_sha" ] || {
    echo "$variant $module.ap SHA-256 mismatch: $actual_sha" >&2
    exit 1
  }
  mkdir -p "$project" "$home"

  case "$module" in
    music)
      expected_count=5
      expected_refs=(
        'REF target=bfc164dc from=400d4f44 type=COMPUTED_CALL function=FUN_400d4edc'
        'REF target=bfc164dc from=401d51fe type=COMPUTED_CALL function=FUN_401d51b8'
        'REF target=bfc164dc from=40394f66 type=COMPUTED_CALL function=FUN_40394f48'
        'REF target=bfc164dc from=40494eb4 type=COMPUTED_CALL function=FUN_40494e94'
        'REF target=bfc164dc from=4051532a type=COMPUTED_CALL function=FUN_405151a0'
      )
      expected_pointers=(400d508c 401d52bc 40394f84 40495128 405153c0)
      dump_address=0x401d51b8
      ;;
    usound)
      expected_count=1
      expected_refs=('REF target=bfc164dc from=40094ce6 type=COMPUTED_CALL function=FUN_40094ccc')
      expected_pointers=(40094d24)
      dump_address=0x40094ccc
      ;;
    udisk)
      expected_count=1
      expected_refs=('REF target=bfc164dc from=40014ce6 type=COMPUTED_CALL function=FUN_40014ccc')
      expected_pointers=(40014da0)
      dump_address=0x40014ccc
      ;;
    *) echo "unsupported module: $module" >&2; exit 2 ;;
  esac

  if ! GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$home}" \
    "$GHIDRA" "$project" "m30-resident-service-$variant-$module" \
      -import "$ap" -loader BinaryLoader -processor 'MIPS:LE:32:default' -noanalysis \
      -scriptPath "$WORK/scripts" \
      -postScript LoadAP.java "$ap" \
      -postScript DumpRefsTo.java 0xbfc164dc \
      -postScript FindPointerRefs.java 0xbfc164dd \
      -postScript DumpAt.java "$dump_address" "$output" >"$log" 2>&1; then
    cat "$log" >&2
    exit 1
  fi

  for marker in "${expected_refs[@]}"; do expect_log "$marker" "$log"; done
  call_count=$(grep -Fc 'REF target=bfc164dc ' "$log")
  [ "$call_count" -eq "$expected_count" ] || {
    echo "$variant $module expected exactly $expected_count resident-service calls, found $call_count" >&2
    grep -F 'REF target=bfc164dc ' "$log" >&2 || true
    exit 1
  }
  for address in "${expected_pointers[@]}"; do
    expect_log "POINTER_REF value=0xbfc164dd address=$address" "$log"
  done
  expect_log "POINTER_TOTAL value=0xbfc164dd count=$expected_count" "$log"

  python3 - "$output" "$module" <<'PY'
import pathlib
import re
import sys

text = pathlib.Path(sys.argv[1]).read_text()
module = sys.argv[2]
if module == "music":
    checks = [
        "(*DAT_401d52b8)(param_1 + 4,*param_1)",
    ]
    flow_pattern = None
elif module == "usound":
    checks = [
        "undefined1 auStack_18 [8];",
        "cVar1 = (*_FUN_40094d24)(auStack_18)",
        r"cVar1 == '\x01'",
        "iVar2 = (*DAT_40094d2c)(auStack_18,local_10,PTR_DAT_40094d28)",
        "FUN_40094c00(local_10[0]);",
    ]
    flow_pattern = re.compile(
        r"if\s*\(\(\(\*DAT_40094d20\s*!=\s*'\\x01'\)\s*&&\s*"
        r"\(cVar1\s*=\s*\(\*_FUN_40094d24\)\(auStack_18\),\s*"
        r"cVar1\s*==\s*'\\x01'\)\)\s*&&\s*"
        r"\(iVar2\s*=\s*\(\*DAT_40094d2c\)\(auStack_18,local_10,PTR_DAT_40094d28\),\s*"
        r"iVar2\s*==\s*1\)\)\s*\{\s*FUN_40094c00\(local_10\[0\]\);",
        re.S,
    )
elif module == "udisk":
    checks = [
        "undefined1 auStack_28 [8];",
        "cVar1 = (*_FUN_40014da0)(auStack_28)",
        r"cVar1 == '\x01'",
        "iVar2 = (*DAT_40014da8)(auStack_28,local_20,PTR_DAT_40014da4)",
        "iVar2 == 1",
        "local_20[0] == 0x2d01",
        r"*DAT_40014d9c = '\x01';",
    ]
    flow_pattern = re.compile(
        r"cVar1\s*=\s*\(\*_FUN_40014da0\)\(auStack_28\);\s*"
        r"if\s*\(\(\(\(cVar1\s*==\s*'\\x01'\)\s*&&\s*"
        r"\(iVar2\s*=\s*\(\*DAT_40014da8\)\(auStack_28,local_20,PTR_DAT_40014da4\),\s*"
        r"iVar2\s*==\s*1\)\)\s*&&\s*"
        r"\(local_20\[0\]\s*==\s*0x2d01\)\)\s*&&\s*"
        r"\(\*DAT_40014dac\s*==\s*0\)\)\s*\{\s*"
        r"\*DAT_40014d9c\s*=\s*'\\x01';",
        re.S,
    )
else:
    raise SystemExit(f"unsupported module: {module}")

positions = []
for check in checks:
    position = text.find(check)
    if position < 0:
        raise SystemExit(f"{module} decompile missing expected dataflow: {check}")
    positions.append(position)
if positions != sorted(positions):
    raise SystemExit(f"{module} decompile has the expected dataflow out of order")
if flow_pattern is not None and flow_pattern.search(text) is None:
    raise SystemExit(f"{module} decompile no longer proves the expected gated buffer/event flow")
PY

  echo "=== $variant $module ==="
  rg 'REF target=|POINTER_REF|POINTER_TOTAL' "$log"
}

run_case community music "$1" \
  662e379602707a9c4b0dcd6b94b591dd77c2b5f7a557fcb03b8c2a5c9b1baac3
run_case community usound "$2" \
  e67af2588eea49bf1f81a588ac252636e3678a756d39013e46605cc16afa96cf
run_case community udisk "$3" \
  a7c5a1fe9b73601bfad849fc71cff7f5c919b39f740e92d5e1864435f0c1cae4
run_case stock music "$4" \
  faa6b68a68de78ef45c2a9d9ad664b6b6310a93f21f9a07b29467827abd49193
run_case stock usound "$5" \
  e67af2588eea49bf1f81a588ac252636e3678a756d39013e46605cc16afa96cf
run_case stock udisk "$6" \
  0cc4c679d956f56e29d9fd3f9d5fe53e2134b072db07eba8e542468d63a56548

echo 'RESIDENT SERVICE XREF PASS stock-community modules=music,usound,udisk'
