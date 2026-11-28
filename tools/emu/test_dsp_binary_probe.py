import tempfile
import unittest
from pathlib import Path
import struct
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from dsp_binary_probe import read_header, verify_corpus


class DspBinaryProbeTests(unittest.TestCase):
    def test_dsp_marker_is_at_zero(self):
        data = b"yqhx" + struct.pack("<15I", *range(15))
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "admp3.dsp"
            path.write_bytes(data)
            size, words, _ = read_header(path)
        self.assertEqual(size, 64)
        self.assertEqual(words[0], 0x78687179)

    def test_resident_style_offset_four_marker_is_rejected(self):
        data = b"\0\0\0\0yqhx" + bytes(58)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "admp3.dsp"
            path.write_bytes(data)
            with self.assertRaises(AssertionError):
                read_header(path)

    def test_corpus_requires_identical_members(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            data = b"yqhx" + struct.pack("<15I", *range(15))
            for corpus in ("stock", "community", "first-pass-verify", "v1.101.11", "v1.101.12", "v5"):
                path = root / corpus / "fw"
                path.mkdir(parents=True)
                (path / "admp3.dsp").write_bytes(data)
            result = verify_corpus(root, ("admp3.dsp",))
        self.assertEqual(result["admp3.dsp"][0], 64)


if __name__ == "__main__":
    unittest.main()
