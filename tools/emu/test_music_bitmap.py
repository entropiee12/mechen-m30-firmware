import struct
import tempfile
import unittest
from pathlib import Path

from music_bitmap import load_music_bitmap


class MusicBitmapTests(unittest.TestCase):
    def make_resource(self, *, format_flags=2, size=8, payload=b"\0" * 8):
        data = bytearray(0x500 + len(payload))
        data[:4] = b"RU3\x19"
        struct.pack_into(
            "<IIII",
            data,
            0x400,
            0x500,
            size,
            (2 << 16) | format_flags,
            2,
        )
        data[0x500:] = payload
        return bytes(data)

    def test_loads_direct_little_endian_rgb565_words(self):
        payload = struct.pack("<4H", 0xF800, 0x07E0, 0x001F, 0x18C3)

        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "music.sty"
            path.write_bytes(self.make_resource(payload=payload))

            bitmap = load_music_bitmap(path, 0)

        self.assertEqual((bitmap.width, bitmap.height), (2, 2))
        self.assertEqual(bitmap.pixels, (0xF800, 0x07E0, 0x001F, 0x18C3))

    def test_rejects_unknown_format_flags(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "music.sty"
            path.write_bytes(self.make_resource(format_flags=1))

            with self.assertRaisesRegex(ValueError, "format flags"):
                load_music_bitmap(path, 0)

    def test_rejects_a_non_16_bit_resource(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "music.sty"
            path.write_bytes(self.make_resource(size=4, payload=b"\0" * 4))

            with self.assertRaisesRegex(ValueError, "direct 16-bit"):
                load_music_bitmap(path, 0)


if __name__ == "__main__":
    unittest.main()
