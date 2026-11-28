import struct
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ui_resource_probe import parse_mcg, parse_sty, verify_corpus


CORPUS = ("stock", "community", "first-pass-verify", "v1.101.11", "v1.101.12", "v5")


class UiResourceProbeTests(unittest.TestCase):
    def test_mcg_node_records_and_filesum(self):
        data = (
            b"test\0ACTMCGGM01"
            b"[NODE1]\nname=x\nindex=1\nid=0\nlayer=1\n"
            b"[NODE2]\nname=y\nindex=2\nid=7\nlayer=2\n"
            b"[INFO]\nfilesum=2\n"
        )
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "mainmenu.mcg"
            path.write_bytes(data)
            size, _, nodes = parse_mcg(path)
        self.assertEqual(size, len(data))
        self.assertEqual(nodes, ((1, 1, 0, 1, b"x"), (2, 2, 7, 2, b"y")))

    def test_ru3_groups_are_bounded(self):
        data = bytearray(0x80)
        data[:4] = b"RU3\x19"
        struct.pack_into("<I", data, 8, 0x20)
        struct.pack_into("<I", data, 0x28, 0x40)
        struct.pack_into("<II", data, 0x40, 0x60, 1)
        data[0x60] = 0x55
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "upgrade.sty"
            path.write_bytes(data)
            _, _, style_addr, style_begin, groups = parse_sty(path)
        self.assertEqual((style_addr, style_begin), (0x20, 0x40))
        self.assertEqual(groups, ((0x60, 1),))

    def test_corpus_requires_all_members(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            mcg = b"ACTMCGGM01[NODE1]\nname=x\nindex=1\nid=0\nlayer=1\n[INFO]\nfilesum=1\n"
            sty = bytearray(0x80)
            sty[:4] = b"RU3\x19"
            struct.pack_into("<I", sty, 8, 0x20)
            struct.pack_into("<I", sty, 0x28, 0x40)
            struct.pack_into("<II", sty, 0x40, 0x60, 1)
            for corpus in CORPUS:
                path = root / corpus / "fw"
                path.mkdir(parents=True)
                (path / "browser.mcg").write_bytes(mcg)
                (path / "mainmenu.mcg").write_bytes(mcg)
                for name in ("browser.sty", "config.sty", "udisk.sty", "upgrade.sty"):
                    (path / name).write_bytes(sty)
            result = verify_corpus(root)
        self.assertEqual(len(result), 6)


if __name__ == "__main__":
    unittest.main()
