#!/usr/bin/env bash
# Execute the source-derived actions_flash MIPS16 read_mem2 helper on synthetic RAM.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
CROSS=${CROSS:-mipsel-linux-gnu-}
QEMU=${QEMU:-qemu-system-mipsel}
PY=${PYTHON:-python3}
OUT=$(mktemp -d "${TMPDIR:-/private/tmp}/m30-actions-mem2-qemu.XXXXXX")
trap 'rm -rf "$OUT"' EXIT

for tool in "${CROSS}as" "${CROSS}ld" "$QEMU" "$PY"; do
  command -v "$tool" >/dev/null || { echo "missing required tool: $tool" >&2; exit 1; }
done

"$PY" - "$OUT" <<'PY'
from hashlib import sha256
from pathlib import Path
import struct
import sys

out = Path(sys.argv[1])
# Transcribed little-endian uint16_t code_mips[] from actions_dump.c::dump_mem2.
words = (
    0xB207, 0x9A80, 0x9AA1, 0xF000, 0x42C8, 0xE595, 0xDAC0, 0xA460,
    0x4C01, 0xC660, 0x4E01, 0xECAA, 0x61FA, 0xE8A0, 0xE020, 0xBFC1,
)
helper = bytearray(struct.pack("<" + "H" * len(words), *words))
if len(helper) != 0x20:
    raise SystemExit(f"unexpected source helper length: {len(helper):#x}")
digest = sha256(helper).hexdigest()
if digest != "ab33899badb9711989bad112039760044097b6e4a68cfe91c002b90459012f90":
    raise SystemExit(f"source helper transcription hash mismatch: {digest}")
# QEMU relocates the helper from BFC to RAM; patch only the embedded scratch pointer.
if struct.unpack_from("<I", helper, 0x1C)[0] != 0xBFC1E020:
    raise SystemExit("unexpected embedded source scratch address")
struct.pack_into("<I", helper, 0x1C, 0x80050020)
(out / "mem2_helper.bin").write_bytes(helper)
(out / "source_bytes.bin").write_bytes(bytes((i * 73 + 19) & 0xFF for i in range(0x1D8)))
PY

(
  cd "$OUT"
  "${CROSS}as" -EL -mabi=32 -mips32 -o probe.o "$ROOT/tools/emu/qemu_actions_flash_mem2_probe.S"
  "${CROSS}ld" -EL -m elf32ltsmip -T "$ROOT/tools/emu/qemu_actions_flash_mem2_probe.ld" -o probe.elf probe.o
)

"$PY" - "$QEMU" "$OUT/probe.elf" <<'PY'
import subprocess
import sys

qemu, elf = sys.argv[1:]
command = [qemu, "-M", "malta", "-cpu", "24Kc", "-m", "256M", "-nographic",
           "-monitor", "none", "-serial", "null", "-serial", "null",
           "-serial", "stdio", "-kernel", elf]
try:
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, timeout=8)
except subprocess.TimeoutExpired as exc:
    output = exc.stdout or ""
    if isinstance(output, bytes):
        output = output.decode(errors="replace")
    print(output, end="")
    if "ACTIONS FLASH MEM2 QEMU PASS" in output:
        raise SystemExit(0)
    raise SystemExit("actions_flash mem2 QEMU probe timed out")

print(result.stdout, end="")
if "ACTIONS FLASH MEM2 QEMU PASS" not in result.stdout:
    raise SystemExit(f"actions_flash mem2 QEMU probe failed (exit={result.returncode})")
PY
