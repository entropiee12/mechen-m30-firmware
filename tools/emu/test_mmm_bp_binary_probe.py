import struct
import tempfile
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from mmm_bp_binary_probe import FADE_MARKERS, verify_member


def synthetic_mmm_bp() -> bytes:
    data = bytearray(0x1700)
    data[4:8] = b"yqhx"
    struct.pack_into("<III", data, 0x08, 0x400, 0x6F0, 0xBFC21400)
    struct.pack_into("<III", data, 0x14, 0xB00, 0xC00, 0xBFC27400)
    for offset, marker in FADE_MARKERS.items():
        data[offset : offset + len(marker)] = marker
    return bytes(data)


class MmmBpBinaryProbeTests(unittest.TestCase):
    def test_markers_and_segment_destinations_are_checked(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "mmm_bp.al"
            path.write_bytes(synthetic_mmm_bp())
            self.assertEqual(verify_member(path), FADE_MARKERS)

    def test_missing_fade_marker_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "mmm_bp.al"
            data = bytearray(synthetic_mmm_bp())
            data[0xB70] = 0
            path.write_bytes(data)
            with self.assertRaises(AssertionError):
                verify_member(path)

    def test_wrong_second_segment_destination_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "mmm_bp.al"
            data = bytearray(synthetic_mmm_bp())
            struct.pack_into("<I", data, 0x1C, 0xBFC27000)
            path.write_bytes(data)
            with self.assertRaises(AssertionError):
                verify_member(path)


if __name__ == "__main__":
    unittest.main()
