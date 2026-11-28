#!/usr/bin/env python3
"""Host-only tests for the filesystem-driver boundary probe."""

from __future__ import annotations

import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from filesystem_binary_probe import EXTENSIONS, CONTRACTS, read_extension_slots, verify_driver


ROOT = Path(__file__).resolve().parents[2]


class FilesystemBinaryProbeTests(unittest.TestCase):
    def test_extension_slots_are_fixed_four_byte_entries(self) -> None:
        data = b"\x00\x00\xff\xff" + b"".join(
            extension.encode("ascii").ljust(4, b"\x00") for extension in EXTENSIONS
        )
        contract = CONTRACTS["exfatn.drv"]
        padded = b"\x00" * contract.table_header_offset + data
        self.assertEqual(read_extension_slots(padded, contract), EXTENSIONS)

    def test_exfat_driver_contract(self) -> None:
        contract, slots = verify_driver(ROOT / "analysis/community/fw/exfatn.drv")
        self.assertEqual(contract.name, "exfatn.drv")
        self.assertEqual(len(slots), 31)

    def test_fat32_driver_contract(self) -> None:
        contract, slots = verify_driver(ROOT / "analysis/community/fw/fat_32n.drv")
        self.assertEqual(contract.name, "fat_32n.drv")
        self.assertEqual(slots, EXTENSIONS)


if __name__ == "__main__":
    unittest.main()
