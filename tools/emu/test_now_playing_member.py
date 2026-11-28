import struct
import tempfile
import unittest
from pathlib import Path

from build_now_playing_member import EXPECTED_CHANGED_MEMBERS, build
from patch_music_controls import ICONS, TABLE_OFFSET


class NowPlayingMemberTests(unittest.TestCase):
    def test_builder_changes_only_music_style(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            candidate = root / "candidate"
            source.mkdir()

            music_size = max(
                0x840 + 45_000,
                max(offset + size for _, offset, size, _, _ in ICONS),
            )
            music = bytearray(music_size)
            struct.pack_into("<IIII", music, TABLE_OFFSET, 0x840, 45_000, 150 << 16, 150)
            for index, offset, size, width, height in ICONS:
                struct.pack_into(
                    "<IIII", music, TABLE_OFFSET + index * 16,
                    offset, size, width << 16, height,
                )
            source.joinpath("music.sty").write_bytes(music)
            source.joinpath("browser.ap").write_bytes(b"unchanged")

            changed = build(source, candidate, validate_source=False)

            self.assertEqual(changed, EXPECTED_CHANGED_MEMBERS)
            self.assertEqual((candidate / "browser.ap").read_bytes(), b"unchanged")
            self.assertEqual(
                (candidate / "music.sty").stat().st_size,
                (source / "music.sty").stat().st_size,
            )
            self.assertTrue((candidate.parent / "candidate-preview.ppm").is_file())

    def test_builder_refuses_to_overwrite_existing_candidate(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            candidate = root / "candidate"
            source.mkdir()
            candidate.mkdir()
            with self.assertRaisesRegex(ValueError, "already exists"):
                build(source, candidate)


if __name__ == "__main__":
    unittest.main()
