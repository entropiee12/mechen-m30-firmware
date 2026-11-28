import struct
import sys
import tempfile
import unittest
from pathlib import Path


TOOLS_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS_ROOT))

from patch_music_controls import (
    ICONS,
    ICON_LAYOUTS,
    TABLE_OFFSET,
    patch as patch_music_controls,
)
from patch_upgrade_prompt import EXPECTED_COPIES, NEW, OLD, patch as patch_upgrade_prompt


class CandidatePatchTests(unittest.TestCase):
    def test_upgrade_prompt_replaces_exactly_four_fixed_width_copies(self):
        prefix = b"resource-prefix"
        suffix = b"resource-suffix"
        source = prefix + OLD * EXPECTED_COPIES + suffix

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source_path = root / "setting.sty"
            output_path = root / "out" / "setting.sty"
            source_path.write_bytes(source)

            patch_upgrade_prompt(source_path, output_path, validate_source=False)
            result = output_path.read_bytes()

        self.assertEqual(len(result), len(source))
        self.assertEqual(result, prefix + NEW * EXPECTED_COPIES + suffix)
        self.assertEqual(result.count(OLD), 0)
        self.assertEqual(result.count(NEW), EXPECTED_COPIES)

    def test_upgrade_prompt_rejects_a_wrong_copy_count(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source_path = root / "setting.sty"
            output_path = root / "out" / "setting.sty"
            source_path.write_bytes(OLD)

            with self.assertRaises(SystemExit):
                patch_upgrade_prompt(source_path, output_path, validate_source=False)

            self.assertFalse(output_path.exists())

    def test_upgrade_prompt_rejects_unknown_source_payload(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source_path = root / "setting.sty"
            output_path = root / "out" / "setting.sty"
            source_path.write_bytes(OLD * EXPECTED_COPIES)

            with self.assertRaises(SystemExit):
                patch_upgrade_prompt(source_path, output_path)

            self.assertFalse(output_path.exists())

    def test_music_controls_changes_pixels_inside_declared_resources_only(self):
        resource_end = max(offset + size for _, offset, size, _, _ in ICONS)
        source = bytearray(resource_end + 16)
        source[-16:] = b"outside-resource"

        for index, offset, size, width, height in ICONS:
            struct.pack_into(
                "<IIII",
                source,
                TABLE_OFFSET + index * 16,
                offset,
                size,
                width << 16,
                height,
            )
            struct.pack_into("<H", source, offset, 0xFFFF)
            struct.pack_into("<H", source, offset + 2, 0x18C3)
            struct.pack_into("<H", source, offset + 4, 0x0000)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source_path = root / "music.sty"
            output_path = root / "out" / "music.sty"
            source_path.write_bytes(source)

            patch_music_controls(source_path, output_path, validate_source=False)
            result = output_path.read_bytes()

        self.assertEqual(len(result), len(source))
        self.assertEqual(result[-16:], b"outside-resource")
        self.assertNotEqual(result[ICONS[0][1] : ICONS[0][1] + 2], b"\xff\xff")
        self.assertEqual(result[ICONS[0][1] + 2 : ICONS[0][1] + 4], b"\xc3\x18")
        self.assertEqual(result[ICONS[0][1] + 4 : ICONS[0][1] + 6], b"\x00\x00")

    def test_music_controls_uses_shifted_2024_layout(self):
        icons = ICON_LAYOUTS["2024-04-24"]
        resource_end = max(offset + size for _, offset, size, _, _ in icons)
        source = bytearray(resource_end + 16)

        for index, offset, size, width, height in icons:
            struct.pack_into(
                "<IIII",
                source,
                TABLE_OFFSET + index * 16,
                offset,
                size,
                width << 16,
                height,
            )
            struct.pack_into("<H", source, offset, 0xFFFF)

        legacy_resource = bytes(source[0x16A00 : 0x16A00 + 192])

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source_path = root / "music.sty"
            output_path = root / "out" / "music.sty"
            source_path.write_bytes(source)

            patch_music_controls(
                source_path,
                output_path,
                validate_source=False,
            )
            result = output_path.read_bytes()

        self.assertEqual(result[0x16A00 : 0x16A00 + 192], legacy_resource)
        self.assertNotEqual(result[0x16AC0 : 0x16AC2], b"\xff\xff")
        self.assertNotEqual(result[0x17740 : 0x17742], b"\xff\xff")

    def test_music_controls_rejects_unknown_source_payload(self):
        resource_end = max(offset + size for _, offset, size, _, _ in ICONS)
        source = bytearray(resource_end + 16)

        for index, offset, size, width, height in ICONS:
            struct.pack_into(
                "<IIII",
                source,
                TABLE_OFFSET + index * 16,
                offset,
                size,
                width << 16,
                height,
            )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source_path = root / "music.sty"
            output_path = root / "out" / "music.sty"
            source_path.write_bytes(source)

            with self.assertRaises(SystemExit):
                patch_music_controls(source_path, output_path)

            self.assertFalse(output_path.exists())


if __name__ == "__main__":
    unittest.main()
