import struct
import tempfile
import unittest
from pathlib import Path

from driver_loader import ResidentDriverLoader, ResidentMemory


class DriverLoaderTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tempdir = tempfile.TemporaryDirectory()
        self.path = Path(self.tempdir.name) / "loader.drv"
        data = bytearray(0x60)
        data[4:8] = b"yqhx"
        struct.pack_into("<III", data, 0x08, 0x50, 4, 0x16024000)
        struct.pack_into("<II", data, 0x38, 4, 0x16025000)
        struct.pack_into("<I", data, 0x40, 0x16024001)
        data[0x44:0x4C] = b"entryctx"
        data[0x50:0x54] = b"TEXT"
        self.path.write_bytes(data)

    def tearDown(self) -> None:
        self.tempdir.cleanup()

    def test_a408_uses_compact_slots_for_6_and_7(self) -> None:
        loader = ResidentDriverLoader.a408()
        self.assertEqual(loader.load(self.path, 6).slot_offset, 24)
        self.assertEqual(loader.load(self.path, 7).slot_offset, 28)
        self.assertEqual(loader.load(self.path, 8).slot_offset, 96)

    def test_a408_preserves_callback_context(self) -> None:
        event = ResidentDriverLoader.a408().load(self.path, 1)
        self.assertEqual(event.header_read, 0x4C)
        self.assertTrue(event.entry_context)

    def test_a578_has_extended_slots_and_no_callback(self) -> None:
        loader = ResidentDriverLoader.a578()
        first = loader.load(self.path, 1)
        second = loader.load(self.path, 1)
        self.assertEqual(first.slot_offset, 12)
        self.assertEqual(first.header_read, 0x44)
        self.assertIsNone(first.entry_context)
        self.assertFalse(first.already_installed)
        self.assertTrue(second.already_installed)

    def test_resident_memory_applies_segments_and_clears_bss(self) -> None:
        memory = ResidentMemory()
        memory.write(0x16025000, b"OLD!")

        ResidentDriverLoader.a408().load(self.path, 1, memory=memory)

        self.assertEqual(memory.read(0x16024000, 4), b"TEXT")
        self.assertEqual(memory.read(0x16025000, 4), b"\x00" * 4)


if __name__ == "__main__":
    unittest.main()
