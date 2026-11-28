#!/usr/bin/env bash
# Run the evidence-backed upgrade boundary probe against every dated upgrade.ap.
# No AP, HEX, SD-card, or device state is modified.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
EVIDENCE_ROOT=${1:-}

if [ -z "$EVIDENCE_ROOT" ] || [ ! -d "$EVIDENCE_ROOT/extracted" ]; then
  echo "usage: $0 EVIDENCE_ROOT" >&2
  exit 2
fi

count=0
while IFS= read -r ap_path; do
  relative_path=${ap_path#"$EVIDENCE_ROOT/extracted/"}
  echo "UPGRADE CORPUS START $relative_path"
  "$ROOT/tools/ghidra/run_upgrade_ap_probe.sh" "$ap_path"
  count=$((count + 1))
done < <(find "$EVIDENCE_ROOT/extracted" -type f -path '*/fw/upgrade.ap' -print | sort)

if [ "$count" -eq 0 ]; then
  echo "no extracted upgrade.ap files found under $EVIDENCE_ROOT" >&2
  exit 1
fi

echo "UPGRADE CORPUS PASS images=$count"
