#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AUDDEV_PROBE_SCRIPT=RunAuddevIdx18Drc.java \
AUDDEV_PASS_PATTERN='AUDDEV IDX18 DRC P-CODE PASS cases=6' \
  bash "$ROOT/tools/ghidra/run_auddev_idx18_rate_probe.sh" "$@"
