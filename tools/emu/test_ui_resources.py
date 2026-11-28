import tempfile
import unittest
from pathlib import Path

from ui_resources import McgTree, Ru3Resource, UiResourceCatalog


ROOT = Path(__file__).parents[2]
COMMUNITY_FW = ROOT / "analysis/community/fw"


class UiResourceMetadataTests(unittest.TestCase):
    def test_mcg_tree_exposes_records_without_assigning_names(self) -> None:
        data = (
            b"ACTMCGGM01[NODE1]\nname=x\nindex=1\nid=0\nlayer=1\n"
            b"[NODE2]\nname=y\nindex=2\nid=7\nlayer=2\n"
            b"[INFO]\nfilesum=2\n"
        )
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "mainmenu.mcg"
            path.write_bytes(data)

            tree = McgTree.load(path)

        self.assertEqual(tree.nodes_with_id(7)[0].layer, 2)
        self.assertEqual(tree.nodes[0].index, 1)
        self.assertEqual(tree.nodes[0].raw_name, b"x")

    def test_ru3_resource_exposes_bounded_groups(self) -> None:
        data = bytearray(0x80)
        data[:4] = b"RU3\x19"
        data[8:12] = (0x20).to_bytes(4, "little")
        data[0x28:0x2C] = (0x40).to_bytes(4, "little")
        data[0x40:0x48] = (0x60).to_bytes(4, "little") + (1).to_bytes(4, "little")
        data[0x60] = 0x55
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "upgrade.sty"
            path.write_bytes(data)

            resource = Ru3Resource.load(path)

        group = resource.group(0)
        self.assertEqual((group.address, group.count), (0x60, 1))

    @unittest.skipUnless(COMMUNITY_FW.is_dir(), "community firmware tree is not present")
    def test_real_community_catalog_keeps_verified_member_shapes(self) -> None:
        catalog = UiResourceCatalog.load(COMMUNITY_FW)

        self.assertEqual(len(catalog.mcg_member("browser.mcg").nodes), 17)
        self.assertEqual(len(catalog.mcg_member("mainmenu.mcg").nodes), 3)
        self.assertEqual(len(catalog.sty_member("browser.sty").groups), 1)
        self.assertEqual(len(catalog.sty_member("config.sty").groups), 2)
        self.assertEqual(len(catalog.sty_member("udisk.sty").groups), 3)
        self.assertEqual(len(catalog.sty_member("upgrade.sty").groups), 3)


if __name__ == "__main__":
    unittest.main()
