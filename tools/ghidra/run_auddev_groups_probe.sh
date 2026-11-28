#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DRIVER=${1:-$ROOT/analysis/community/fw/aud_dev.drv}
WORK=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-auddev-groups.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

python3 "$ROOT/tools/ghidra/make_auddev_groups_fixture.py" "$WORK/enabled.bin" --drc enabled
python3 "$ROOT/tools/ghidra/make_auddev_groups_fixture.py" "$WORK/disabled.bin" --drc disabled
bash "$ROOT/tools/ghidra/run_auddev_eq_copy_probe.sh" "$DRIVER" "$WORK/enabled.bin" decoded-groups
bash "$ROOT/tools/ghidra/run_auddev_eq_copy_probe.sh" "$DRIVER" "$WORK/disabled.bin" disabled-groups
