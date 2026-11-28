#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
AP=${1:-$ROOT/analysis/community/fw/setting.ap}
USOUND=${USOUND:-$ROOT/analysis/community/fw/usound.drv}
CROSS=${CROSS:-mipsel-linux-gnu-}
QEMU=${QEMU:-qemu-system-mipsel}
CPU=${CPU:-24Kc}
PY=${PYTHON:-python3}
OUT=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-setting-stub.XXXXXX")
trap 'rm -rf "$OUT"' EXIT
for tool in "${CROSS}as" "${CROSS}ld" "$QEMU" "$PY"; do
  command -v "$tool" >/dev/null || { echo "missing tool: $tool" >&2; exit 1; }
done
"$PY" "$ROOT/tools/emu/extract_setting_trap_stub.py" "$AP" --output "$OUT/setting_stub.bin"
"$PY" "$ROOT/tools/emu/extract_setting_trap_stub.py" "$AP" \
    --address 0xBFC16588 --output "$OUT/import6_stub.bin"
"$PY" - "$USOUND" "$OUT/setting_stub.bin" "$OUT/import6_stub.bin" "$OUT/usound_code.bin" <<'PY'
from pathlib import Path
import sys
driver = Path(sys.argv[1]).read_bytes()
import15_stub = Path(sys.argv[2]).read_bytes()
import6_stub = Path(sys.argv[3]).read_bytes()
start, size, stub_offset = 0x200, 0x26b0, 0x26ac
segment = driver[start:start + size]
if len(segment) != size or segment[stub_offset:stub_offset + 4] != import15_stub:
    raise SystemExit("setting.ap import-15 stub differs from known-running usound MIPS16 stub")
Path(sys.argv[4]).write_bytes(segment + import6_stub)
print("SETTING/USOUND STUB IDENTITY PASS import15=0x800526ac import6=0x800526b0")
PY
(cd "$OUT"
 "${CROSS}as" -EL -mabi=32 -mips32 -o probe.o "$ROOT/tools/emu/qemu_setting_stub_probe.S"
 "${CROSS}ld" -EL -m elf32ltsmip -T "$ROOT/tools/emu/qemu_setting_stub_probe.ld" -o probe.elf probe.o)
"$PY" - "$QEMU" "$OUT/probe.elf" "$OUT/qemu.trace" "$CPU" <<'PY'
import subprocess
import sys

qemu, elf, trace, cpu = sys.argv[1:]
command = [qemu, "-M", "malta", "-cpu", cpu, "-m", "16M",
           "-nographic", "-monitor", "none", "-serial", "null",
           "-serial", "null", "-serial", "stdio", "-d", "int,cpu_reset,in_asm",
           "-D", trace, "-kernel", elf]
try:
    result = subprocess.run(command, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, timeout=5)
    output = result.stdout
except subprocess.TimeoutExpired as exc:
    output = exc.stdout or ""
    if isinstance(output, bytes):
        output = output.decode(errors="replace")
print(output, end="")
if "SETTING IMPORT15/IMPORT6 QEMU ABI PASS" not in output:
    try:
        with open(trace, encoding="utf-8", errors="replace") as log:
            lines = log.readlines()
        interesting = [i for i, line in enumerate(lines)
                       if "80050000" in line or "8001002c" in line]
        selected = set()
        for i in interesting:
            selected.update(range(max(0, i - 2), min(len(lines), i + 14)))
        excerpt = [lines[i] for i in sorted(selected)] or lines[-40:]
        print("".join(excerpt), file=sys.stderr, end="")
    except OSError:
        pass
    raise SystemExit("QEMU stub probe did not reach PASS")
PY
