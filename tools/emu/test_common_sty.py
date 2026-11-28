import unittest
from pathlib import Path

from common_sty import CommonStyle
from folder_ui import FolderList, FolderRow


ROOT = Path(__file__).parents[2]
COMMUNITY = ROOT / "analysis/community/fw/common.sty"
UI_BLUE = ROOT / "build/ui-blue/common.sty"


@unittest.skipUnless(COMMUNITY.exists() and UI_BLUE.exists(), "common.sty fixtures are not present")
class CommonStyleTests(unittest.TestCase):
    def test_community_resource_exposes_the_red_selection_color(self) -> None:
        style = CommonStyle.load(COMMUNITY)
        self.assertEqual(style.selection_rgb565, 0xD000)
        self.assertEqual(
            (style.text_x, style.text_width, style.row_height, style.items_per_page),
            (35, 205, 30, 7),
        )

    def test_ui_blue_resource_exposes_the_actual_blue_selection_color(self) -> None:
        style = CommonStyle.load(UI_BLUE)
        self.assertEqual(style.selection_rgb565, 0x22F6)
        self.assertEqual(style.active_text_rgb565, 0xFFFF)

    def test_folder_preview_uses_loaded_resource_colors(self) -> None:
        style = CommonStyle.load(UI_BLUE)
        view = FolderList((FolderRow("Music"),), common_style=style)
        _, trace = view.render()
        self.assertEqual(trace[0]["background_rgb"], list(style.selection_color))
        self.assertEqual(trace[0]["background"], "selection-resource")


if __name__ == "__main__":
    unittest.main()
