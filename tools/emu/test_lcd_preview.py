import tempfile
import unittest
from pathlib import Path

from lcd_preview import render_preview
from lcd_transfer import COLUMN_ADDRESS_SET, MEMORY_ACCESS_CONTROL, MEMORY_WRITE, ROW_ADDRESS_SET
from m30_ui import rgb565


ROOT = Path(__file__).parents[2]
AP = ROOT / "build/qemu-minimal-safe/work/verify/fw/music.ap"
STY = ROOT / "build/qemu-minimal-safe/work/verify/fw/music.sty"


@unittest.skipUnless(AP.exists() and STY.exists(), "extracted firmware resources are not present")
class LcdPreviewTests(unittest.TestCase):
    def test_real_ui_pixels_cross_recovered_lcd_boundary(self):
        sink, ui = render_preview(
            AP,
            STY,
            title="LONG TITLE",
            artist="ARTIST",
            album="ALBUM",
            filename="FILE",
            scroll_offset=7,
        )

        self.assertEqual(
            [command.opcode for command in sink.commands],
            [MEMORY_ACCESS_CONTROL, COLUMN_ADDRESS_SET, ROW_ADDRESS_SET, MEMORY_WRITE],
        )
        self.assertEqual(sink.commands[0].payload, (0x48,))
        self.assertEqual(sink.window.pixel_count, 320 * 240)
        self.assertEqual(sink.remaining_pixels, 0)
        self.assertEqual(len(ui.render().pixels), 320 * 240 * 3)
        self.assertIsNotNone(ui.font)

    def test_verified_color_bands_survive_lcd_rgb565_round_trip(self):
        sink, ui = render_preview(
            AP,
            STY,
            title="TITLE",
            artist="ARTIST",
            album="ALBUM",
            filename="FILE",
            scroll_offset=0,
        )

        top = sink.framebuffer.pixels[
            (10 * 320 + 319) * 3 : (10 * 320 + 319) * 3 + 3
        ]
        middle = sink.framebuffer.pixels[
            (100 * 320 + 319) * 3 : (100 * 320 + 319) * 3 + 3
        ]
        bottom = sink.framebuffer.pixels[
            (220 * 320 + 319) * 3 : (220 * 320 + 319) * 3 + 3
        ]
        self.assertEqual(top, bytes(rgb565(0x18C3)))
        self.assertEqual(middle, bytes(rgb565(0x39E7)))
        self.assertEqual(bottom, bytes(rgb565(0x18C3)))

        self.assertEqual(
            [call["identifier"] for call in ui.state_dispatch_trace(0x020)],
            [0x1002, 0x1004, 0x1003],
        )

    def test_preview_writes_a_ppm(self):
        sink, _ = render_preview(
            AP,
            STY,
            title="TITLE",
            artist="ARTIST",
            album="ALBUM",
            filename="FILE",
            scroll_offset=0,
        )
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "lcd.ppm"
            sink.framebuffer.ppm(out)
            data = out.read_bytes()
            self.assertEqual(data[:15], b"P6\n320 240\n255\n")
            self.assertEqual(len(data), 15 + 320 * 240 * 3)


if __name__ == "__main__":
    unittest.main()
