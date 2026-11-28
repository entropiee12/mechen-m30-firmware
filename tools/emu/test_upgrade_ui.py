import unittest
from pathlib import Path

from m30_font import M30Font
from upgrade_ui import UpgradeScreen, normalize_upgrade_progress


ROOT = Path(__file__).parents[2]
FW = ROOT / "analysis" / "community" / "fw"
STYLE = FW / "upgrade.sty"
FONT = FW / "new_m.fnt"


class UpgradeScreenTests(unittest.TestCase):
    def test_each_upgrade_option_selects_exactly_its_own_real_picbox_frame(self):
        font = M30Font.load(FONT)
        for selected_option in range(5):
            with self.subTest(selected_option=selected_option):
                preview = UpgradeScreen(
                    STYLE,
                    selected_option=selected_option,
                    font=font,
                ).render()

                frames = [entry["frame"] for entry in preview.trace["picboxes"]]
                self.assertEqual(frames, [
                    1 if index == selected_option else 0
                    for index in range(5)
                ])
                self.assertEqual(sum(frame == 1 for frame in frames), 1)

    def test_selected_option_uses_real_selected_frame_only(self):
        preview = UpgradeScreen(STYLE, selected_option=2, font=M30Font.load(FONT)).render()

        self.assertEqual(preview.framebuffer.width, 320)
        self.assertEqual(preview.framebuffer.height, 240)
        self.assertEqual(preview.trace["kind"], "upgrade-screen-preview")
        self.assertEqual(preview.trace["selected_option"], 2)
        self.assertEqual(
            [entry["frame"] for entry in preview.trace["picboxes"]],
            [0, 0, 1, 0, 0],
        )
        self.assertEqual(
            [entry["resource"] for entry in preview.trace["picboxes"]],
            [1, 3, 4, 7, 9],
        )

    def test_upgrade_screen_has_the_two_verified_text_viewports(self):
        preview = UpgradeScreen(STYLE, selected_option=0, font=M30Font.load(FONT)).render()

        self.assertEqual(
            [entry["text"] for entry in preview.trace["textboxes"]],
            ["SELECT UPGRADE FILE:", "CONFIRM UPGRADE"],
        )
        self.assertEqual(
            [(entry["x"], entry["y"], entry["width"], entry["height"]) for entry in preview.trace["textboxes"]],
            [(35, 110, 250, 48), (85, 40, 150, 16)],
        )
        self.assertTrue(all(
            entry["text_origin"] == "host-supplied example; not recovered from upgrade.ap"
            for entry in preview.trace["textboxes"]
        ))

    def test_upgrade_preview_reports_host_font_text_clipping(self):
        preview = UpgradeScreen(STYLE, font=M30Font.load(FONT)).render()

        self.assertFalse(preview.trace["textboxes"][0]["renderer_clipped"])
        self.assertEqual(preview.trace["textboxes"][0]["measured_text_width_px"], 218)
        self.assertTrue(preview.trace["textboxes"][1]["renderer_clipped"])
        self.assertEqual(preview.trace["textboxes"][1]["measured_text_width_px"], 170)
        self.assertEqual(
            preview.trace["textboxes"][1]["measurement_source"],
            "host display glyph advances",
        )

    def test_upgrade_screen_rejects_a_sixth_option(self):
        with self.assertRaisesRegex(ValueError, "five upgrade options"):
            UpgradeScreen(STYLE, selected_option=5)

    def test_upgrade_progress_uses_512_byte_denominator_and_special_cases(self):
        result = normalize_upgrade_progress(512, 512, 102, previous_value=0)

        self.assertEqual(result.denominator_sectors, 2)
        self.assertEqual(result.raw_value, 510)
        self.assertEqual(result.value, 510)
        self.assertTrue(result.callback_emitted)

        one = normalize_upgrade_progress(5120, 0, 1, previous_value=0)
        nine = normalize_upgrade_progress(5120, 0, 9, previous_value=0)
        self.assertEqual(one.value, 2)
        self.assertEqual(nine.value, 10)

    def test_upgrade_progress_suppresses_odd_callback_and_zero_denominator(self):
        odd = normalize_upgrade_progress(5120, 0, 1, previous_value=2)
        self.assertEqual(odd.value, 2)
        self.assertFalse(odd.callback_emitted)

        empty = normalize_upgrade_progress(0, 0, 100, previous_value=4)
        self.assertEqual(empty.value, 4)
        self.assertIsNone(empty.raw_value)
        self.assertEqual(empty.denominator_sectors, 0)
        self.assertFalse(empty.callback_emitted)


if __name__ == "__main__":
    unittest.main()
