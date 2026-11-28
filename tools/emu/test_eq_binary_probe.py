import hashlib
import struct
import tempfile
import unittest
from pathlib import Path

from eq_binary_probe import mainmenu_eq_labels, verify
from m30_model import (
    FIRMWARE_EQ_2024_04_PROFILE,
    FIRMWARE_EQ_CURRENT_PROFILE,
    FIRMWARE_EQ_MAINMENU_LABELS_EN,
    firmware_eq_names,
)


ROOT = Path(__file__).resolve().parents[2]
MAINMENU_STY = ROOT / "analysis" / "community" / "fw" / "mainmenu.sty"


def make_eq_members(profile):
    mengine = bytearray(0x5000)
    setting = bytearray(0x5B0B)
    struct.pack_into("<7H", mengine, 0x43EC, *profile.band_hz)
    struct.pack_into("<7H", mengine, 0x43FC, *profile.q_values)

    for selector in range(7):
        gains = profile.presets[selector].gain_int8
        assert gains is not None
        offset = 0x440C + selector * 7
        mengine[offset : offset + 7] = bytes(value & 0xFF for value in gains)

    if profile.setting_custom_row is not None:
        custom_offset = 0x5A94 + 7 * 16
        setting[custom_offset : custom_offset + len(profile.setting_custom_row)] = bytes(
            value & 0xFF for value in profile.setting_custom_row
        )
    return bytes(mengine), bytes(setting)


class EqBinaryProbeTests(unittest.TestCase):
    @unittest.skipUnless(MAINMENU_STY.exists(), "extracted mainmenu.sty is not present")
    def test_selector_names_match_hash_pinned_english_style_table(self):
        style_hash = hashlib.sha256(MAINMENU_STY.read_bytes()).hexdigest()

        self.assertEqual(
            style_hash,
            "23d56b78e01a35f4413a04c8badbf0d0dda9c9637be22b3775ed0a676fa5a624",
        )
        self.assertEqual(
            mainmenu_eq_labels(MAINMENU_STY),
            FIRMWARE_EQ_MAINMENU_LABELS_EN,
        )
        self.assertEqual(
            FIRMWARE_EQ_CURRENT_PROFILE.mainmenu_labels_en,
            FIRMWARE_EQ_MAINMENU_LABELS_EN,
        )
        self.assertEqual(firmware_eq_names(), mainmenu_eq_labels(MAINMENU_STY))

    def test_current_profile_accepts_current_contract(self):
        mengine, setting = make_eq_members(FIRMWARE_EQ_CURRENT_PROFILE)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            mengine_path = root / "mengine.ap"
            setting_path = root / "setting.ap"
            mengine_path.write_bytes(mengine)
            setting_path.write_bytes(setting)

            verify(mengine_path, setting_path)

    def test_current_profile_rejects_a_changed_setting_resource_row(self):
        mengine, setting = make_eq_members(FIRMWARE_EQ_CURRENT_PROFILE)
        changed_setting = bytearray(setting)
        custom_offset = 0x5A94 + 7 * 16
        changed_setting[custom_offset] ^= 1

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            mengine_path = root / "mengine.ap"
            setting_path = root / "setting.ap"
            mengine_path.write_bytes(mengine)
            setting_path.write_bytes(changed_setting)

            with self.assertRaises(AssertionError):
                verify(mengine_path, setting_path)

    def test_2024_profile_accepts_the_verified_50_hz_exception(self):
        self.assertIsNone(FIRMWARE_EQ_2024_04_PROFILE.mainmenu_labels_en)
        mengine, setting = make_eq_members(FIRMWARE_EQ_2024_04_PROFILE)
        setting_without_known_row = bytearray(setting)
        custom_offset = 0x5A94 + 7 * 16
        setting_without_known_row[custom_offset : custom_offset + 7] = bytes(
            (0xFF, 0x80, 0x7F, 0x01, 0x02, 0x03, 0x04)
        )

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            mengine_path = root / "mengine.ap"
            setting_path = root / "setting.ap"
            mengine_path.write_bytes(mengine)
            setting_path.write_bytes(setting_without_known_row)

            with self.assertRaises(AssertionError):
                verify(mengine_path, setting_path)

            verify(
                mengine_path,
                setting_path,
                FIRMWARE_EQ_2024_04_PROFILE,
            )


if __name__ == "__main__":
    unittest.main()
