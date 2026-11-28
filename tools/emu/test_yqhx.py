import struct
import unittest
from pathlib import Path

from yqhx import YqhxImage, slot_layout


class YqhxTests(unittest.TestCase):
    def make_image(self) -> tuple[Path, bytes]:
        data = bytearray(0x80)
        data[4:8] = b"yqhx"
        struct.pack_into("<III", data, 0x08, 0x50, 4, 0x16024000)
        struct.pack_into("<III", data, 0x14, 0, 0, 0)
        struct.pack_into("<III", data, 0x20, 0x54, 3, 0x16025000)
        struct.pack_into("<III", data, 0x2C, 0x57, 2, 0x9FC09F00)
        struct.pack_into("<II", data, 0x38, 0x20, 0x16026000)
        struct.pack_into("<I", data, 0x40, 0x16024001)
        data[0x44:0x4C] = b"entryctx"
        data[0x50:0x54] = b"TEXT"
        data[0x54:0x57] = b"BSS"
        data[0x57:0x59] = b"TB"
        return Path("synthetic.drv"), bytes(data)

    def test_parse_and_load_plan(self) -> None:
        path, data = self.make_image()
        image = YqhxImage.parse(path, data)
        self.assertEqual(image.entry, 0x16024001)
        self.assertEqual(image.entry_context, b"entryctx")
        self.assertEqual(
            image.load_plan(),
            (
                ("segment", 0x16024000, 4),
                ("segment", 0x16025000, 3),
                ("segment", 0x9FC09F00, 2),
                ("bss-zero", 0x16026000, 0x20),
            ),
        )

    def test_loader_slot_layout(self) -> None:
        self.assertEqual(slot_layout(6), ("compact", 4))
        self.assertEqual(slot_layout(7), ("compact", 4))
        self.assertEqual(slot_layout(5), ("extended", 12))
        self.assertEqual(slot_layout(0x105), ("extended", 12))

    def test_segment_overflow_is_rejected(self) -> None:
        path, data = self.make_image()
        broken = bytearray(data)
        struct.pack_into("<I", broken, 0x0C, 0x1000)
        with self.assertRaises(ValueError):
            YqhxImage.parse(path, bytes(broken))

    def test_fourth_segment_overflow_is_rejected(self) -> None:
        path, data = self.make_image()
        broken = bytearray(data)
        struct.pack_into("<I", broken, 0x2C, 0x1000)

        with self.assertRaises(ValueError):
            YqhxImage.parse(path, bytes(broken))


if __name__ == "__main__":
    unittest.main()
