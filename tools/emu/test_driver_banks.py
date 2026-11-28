import struct
import tempfile
import unittest
from pathlib import Path

from driver_banks import DriverBankImage


def synthetic_driver() -> bytes:
    data = bytearray(0x1400)
    data[4:8] = b"yqhx"
    struct.pack_into("<I", data, 0x48, 0x600)
    struct.pack_into("<I", data, 0x4C, 0x800)
    struct.pack_into("<I", data, 0x50, 0x1000)
    struct.pack_into(">HH", data, 0x100 + 0 * 4, 0x100, 0x200)
    struct.pack_into(">HH", data, 0x100 + 1 * 4, 0x200, 0x300)
    struct.pack_into(">HH", data, 0x100 + 2 * 4, 0x300, 0xB00)
    data[0xB00:0x1300] = bytes(range(256)) * 8
    return bytes(data)


class DriverBankImageTests(unittest.TestCase):
    def test_resolves_candidate_without_calling_it_code(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "drv_ui.drv"
            path.write_bytes(synthetic_driver())
            candidate = DriverBankImage.load(path).resolve_far_candidate(0x26089471)
            self.assertIsNotNone(candidate)
            assert candidate is not None
            self.assertEqual((candidate.group, candidate.index), ("B", 2))
            self.assertEqual(candidate.payload_offset, 0x471)
            self.assertEqual(candidate.file_offset, 0xF71)
            self.assertEqual(candidate.entry_offset, 0x470)
            self.assertEqual(candidate.entry_file_offset, 0xF70)

    def test_rejects_candidate_outside_payload_window(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "drv_ui.drv"
            path.write_bytes(synthetic_driver())
            image = DriverBankImage.load(path)
            self.assertIsNone(image.resolve_far_candidate(0x2608A000))

    def test_missing_bank_is_recorded_without_being_created(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "drv_ui.drv"
            path.write_bytes(synthetic_driver())
            image = DriverBankImage.load(path)
            candidate = image.resolve_far_candidate(0x26409401)
            self.assertIsNotNone(candidate)
            assert candidate is not None
            self.assertFalse(candidate.bank_present)
            self.assertIsNone(candidate.file_offset)
            self.assertIsNone(candidate.entry_file_offset)


if __name__ == "__main__":
    unittest.main()
