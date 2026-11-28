#!/usr/bin/env bash
# Execute the recovered MIPS16 EQ structure builder against stock and community APs.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
GHIDRA=${GHIDRA:-/opt/homebrew/Cellar/ghidra/12.2/libexec/support/analyzeHeadless}
COMMUNITY=${1:-$ROOT/analysis/community/fw/mengine.ap}
STOCK=${2:-$ROOT/analysis/stock/fw/mengine.ap}
OUTPUT_DIR=${3:-}

[ -f "$COMMUNITY" ] || { echo "missing community mengine.ap: $COMMUNITY" >&2; exit 1; }
[ -f "$STOCK" ] || { echo "missing stock mengine.ap: $STOCK" >&2; exit 1; }
[ -x "$GHIDRA" ] || { echo "missing Ghidra analyzeHeadless: $GHIDRA" >&2; exit 1; }

verify_hash() {
  local path=$1
  local expected=$2
  local actual
  actual=$(shasum -a 256 "$path" | awk '{print $1}')
  [ "$actual" = "$expected" ] || {
    echo "SHA-256 mismatch for $path: $actual" >&2
    return 1
  }
}

verify_hash "$COMMUNITY" b569605e9f66ef94023cf82d665ae119f6bbb7ed0c9d8f5e6c5be867ce91be49
verify_hash "$STOCK" 1340bac3cb68043f7a9651ee7959c2f502e6759c7debe0626f07ef9231896d4d

run_probe() {
  local path=$1
  local project_name=$2
  local output_dir=${3:-}
  local work
  work=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-eq-builder.XXXXXX")
  mkdir -p "$work/home" "$work/project"

  local script_args=(-postScript LoadAP.java "$path")
  if [ -n "$output_dir" ]; then
    script_args+=(-postScript RunEqBuilderProbe.java "$output_dir")
  else
    script_args+=(-postScript RunEqBuilderProbe.java)
  fi

  if ! JAVA_HOME_OVERRIDE="${JAVA_HOME_OVERRIDE:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}" \
    GHIDRA_JAVA_OPTIONS="${GHIDRA_JAVA_OPTIONS:--Duser.home=$work/home}" \
    "$GHIDRA" "$work/project" "$project_name" \
      -import "$path" \
      -loader BinaryLoader \
      -processor 'MIPS:LE:32:default' \
      -noanalysis \
      -scriptPath "$ROOT/tools/ghidra" \
      "${script_args[@]}" \
      >"$work/run.log" 2>&1; then
    cat "$work/run.log" >&2
    rm -rf "$work"
    return 1
  fi

  if ! grep -q 'EQ BUILDER P-CODE PASS cases=12 result-sha256=' "$work/run.log"; then
    cat "$work/run.log" >&2
    rm -rf "$work"
    echo "EQ builder Ghidra probe did not report PASS for $path" >&2
    return 1
  fi

  grep 'EQ BUILDER P-CODE PASS' "$work/run.log" | tail -n 1
  rm -rf "$work"
}

community_result=$(run_probe "$COMMUNITY" m30eqbuildercommunity "$OUTPUT_DIR")
stock_result=$(run_probe "$STOCK" m30eqbuilderstock)
community_hash=$(printf '%s\n' "$community_result" | sed -nE 's/.*result-sha256=([0-9a-f]{64}).*/\1/p')
stock_hash=$(printf '%s\n' "$stock_result" | sed -nE 's/.*result-sha256=([0-9a-f]{64}).*/\1/p')
[ -n "$community_hash" ] && [ -n "$stock_hash" ] || {
  echo "could not extract EQ builder result digests" >&2
  exit 1
}
[ "$community_hash" = "$stock_hash" ] || {
  echo "community/stock EQ builder results differ" >&2
  echo "$community_result" >&2
  echo "$stock_result" >&2
  exit 1
}

echo "EQ BUILDER CORPUS PASS community=$community_hash stock=$stock_hash"
