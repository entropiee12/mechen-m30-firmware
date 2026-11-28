import unittest
from pathlib import Path

from ru3_preview import render_style
from m30_font import M30Font


ROOT = Path(__file__).parents[2]
FW = ROOT / "analysis/community/fw"
UPGRADE = FW / "upgrade.sty"
MUSIC = FW / "music.sty"
FONT = FW / "new_m.fnt"


@unittest.skipUnless(UPGRADE.exists() and MUSIC.exists(), "card-extracted RU3 fixtures are not present")
class Ru3PreviewTests(unittest.TestCase):
    def test_upgrade_style_renders_real_bitmap_records(self) -> None:
        preview = render_style(UPGRADE, text_values={0: "CONFIRM", 1: "UPGRADE"})
        self.assertEqual((preview.framebuffer.width, preview.framebuffer.height), (320, 240))
        self.assertGreater(len(preview.trace["picboxes"]), 0)
        self.assertEqual(len(preview.trace["textboxes"]), 2)

    def test_music_style_uses_real_artwork_resource(self) -> None:
        preview = render_style(MUSIC)
        self.assertEqual(preview.trace["picboxes"][2]["resource"], 0)
        self.assertEqual(preview.trace["picboxes"][2]["width"], 150)
        self.assertEqual(preview.trace["picboxes"][2]["height"], 150)

    def test_upgrade_style_accepts_shipped_font(self) -> None:
        preview = render_style(
            UPGRADE,
            text_values={0: "SELECT UPGRADE FILE:", 1: "CONFIRM UPGRADE"},
            font=M30Font.load(FONT),
        )
        self.assertEqual(preview.trace["provenance"], "real RU3 PicBox/TextBox records; optional shipped font and host app state")


if __name__ == "__main__":
    unittest.main()
