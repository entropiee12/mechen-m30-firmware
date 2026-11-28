#!/usr/bin/env python3
"""Build the isolated real-bank MIPS16 smoke-probe inputs.

The result is not firmware. It is a freestanding ELF link that places the
real AP banks at their recorded addresses, redirects only the audited
literal-pool pointers to deterministic MIPS16 stubs, and calls
FUN_40d54f2c with a synthetic descriptor.
"""

from __future__ import annotations

import argparse
import hashlib
import struct
from pathlib import Path

from prepare_qemu_banks import parse_banks, prepare


PATCHES = {
    # literal address: (stock value, probe value)
    0x40D5516C: (0xBFC16405, 0x00700001),
    0x40D55170: (0x48454841, 0x00700021),
    0x40D55174: (0x9FC1592C, 0x10002200),
    0x40D55178: (0x484547AD, 0x00700041),
    0x40D5517C: (0xBFC16401, 0x00700041),
    0x40D55180: (0xBFC16BB1, 0x00700061),
    0x40D55198: (0x9FC1A294, 0x10002200),
    0x40D551B0: (0x9FC1A294, 0x10002200),
    0x40D54F10: (0xBFC16AE9, 0x00700081),
    0x40D54F18: (0x485547ED, 0x007000A1),
    0x40D54F1C: (0x48BD4601, 0x007000C1),
    0x40D54F24: (0xBFC16B55, 0x007000E1),
    0x40D54F28: (0x9FC1A28C, 0x10002300),
}


def bank_for(banks, address):
    return next((bank for bank in banks if bank.virtual_address <= address < bank.virtual_address + bank.size), None)


def make_probe(ap: Path, out: Path) -> None:
    source_sha256 = hashlib.sha256(ap.read_bytes()).digest()
    # Linux-user QEMU reserves a 31-bit guest address space for this MIPS32
    # target.  The AP contains one ten-byte, zero-filled resident placeholder
    # at 0x9fc1d600; it is outside that space and is not referenced by this
    # probe.  Keep the exact AP layout in prepare_qemu_banks.py, but omit only
    # this non-executable high-address tail from the user-mode harness.
    prepare(ap, out, max_virtual_address=0x7FFFFFFF)
    if hashlib.sha256(ap.read_bytes()).digest() != source_sha256:
        raise RuntimeError(f"source AP changed while preparing probe: {ap}")
    data, banks = parse_banks(ap)
    by_index = {bank.index: bank for bank in banks}
    patched = []
    for address, (expected, replacement) in PATCHES.items():
        bank = bank_for(banks, address)
        if bank is None:
            raise ValueError(f"no bank contains literal 0x{address:08x}")
        payload = out / "banks" / f"{bank.index:03d}_{bank.virtual_address:08x}.bin"
        raw = bytearray(payload.read_bytes())
        offset = address - bank.virtual_address
        actual = struct.unpack_from("<I", raw, offset)[0]
        if actual != expected:
            raise ValueError(f"literal 0x{address:08x}: expected 0x{expected:08x}, got 0x{actual:08x}")
        struct.pack_into("<I", raw, offset, replacement)
        payload.write_bytes(raw)
        patched.append({"address": f"0x{address:08x}", "before": f"0x{expected:08x}", "after": f"0x{replacement:08x}"})
    (out / "probe-patches.txt").write_text("\n".join(f"{p['address']} {p['before']} -> {p['after']}" for p in patched) + "\n")
    # The static source files are copied next to the generated objects so the
    # commands are self-contained when run from the probe directory.
    root = Path(__file__).parent
    (out / "qemu_probe.S").write_text((root / "qemu_probe.S").read_text())
    (out / "qemu_probe_start.S").write_text((root / "qemu_probe_start.S").read_text())
    (out / "qemu_probe.ld").write_text((root / "qemu_probe.ld").read_text())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ap", type=Path)
    parser.add_argument("out", type=Path)
    args = parser.parse_args()
    make_probe(args.ap, args.out)
    print(f"prepared probe inputs in {args.out}")
    print(f"patched {len(PATCHES)} verified literal-pool pointers; firmware AP unchanged")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
