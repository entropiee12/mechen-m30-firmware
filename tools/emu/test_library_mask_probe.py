from __future__ import annotations

import struct
import tempfile
import unittest
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "sim"))

from filesystem_extension import M3U_LIBRARY_MASK, MUSIC_LIBRARY_MASK
from library_mask_probe import M3U_MASK_OFFSET, MUSIC_MASK_OFFSET, read_masks, verify


class LibraryMaskProbeTests(unittest.TestCase):
    def test_model_records_raw_mask_values_and_bit_positions(self):
        self.assertEqual(MUSIC_LIBRARY_MASK.value, 0x7E190EA0)
        self.assertEqual(len(MUSIC_LIBRARY_MASK.enabled_bit_positions), 14)
        self.assertEqual(M3U_LIBRARY_MASK.enabled_bit_positions, (10, 19))
        self.assertEqual(
            MUSIC_LIBRARY_MASK.driver_slot_positions,
            (1, 2, 3, 4, 5, 6, 11, 12, 15, 20, 21, 22, 24, 26),
        )
        self.assertEqual(M3U_LIBRARY_MASK.driver_slot_positions, (12, 21))

    def test_probe_reads_little_endian_masks(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "playlist.ap"
            data = bytearray(M3U_MASK_OFFSET + 4)
            struct.pack_into("<I", data, MUSIC_MASK_OFFSET, 0x7E190EA0)
            struct.pack_into("<I", data, M3U_MASK_OFFSET, 0x00080400)
            path.write_bytes(data)
            self.assertEqual(read_masks(path), (0x7E190EA0, 0x00080400))
            self.assertEqual(verify(path), (0x7E190EA0, 0x00080400))

    def test_probe_rejects_wrong_mask(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "playlist.ap"
            data = bytearray(M3U_MASK_OFFSET + 4)
            struct.pack_into("<I", data, MUSIC_MASK_OFFSET, 0x7E190EA0)
            struct.pack_into("<I", data, M3U_MASK_OFFSET, 0)
            path.write_bytes(data)
            with self.assertRaises(AssertionError):
                verify(path)


if __name__ == "__main__":
    unittest.main()
