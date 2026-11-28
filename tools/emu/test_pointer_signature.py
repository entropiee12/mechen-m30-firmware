import struct
import tempfile
import unittest
from pathlib import Path

from scan_pointer_signature import MASK, VALUE, scan_file, scan_tree


class PointerSignatureTests(unittest.TestCase):
    def test_scans_little_endian_masked_signature(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "music.ap"
            path.write_bytes(b"x" + struct.pack("<I", VALUE | 0x37) + b"y")
            matches = scan_file(path)
        self.assertEqual([(item.offset, item.word & MASK) for item in matches], [(1, VALUE)])

    def test_tree_ignores_resource_extensions(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "drv_ui.drv").write_bytes(struct.pack("<I", VALUE))
            (root / "music.sty").write_bytes(struct.pack("<I", VALUE))
            matches = scan_tree(root)
        self.assertEqual(len(matches), 1)


if __name__ == "__main__":
    unittest.main()
