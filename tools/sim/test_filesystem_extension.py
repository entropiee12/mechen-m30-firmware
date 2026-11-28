import unittest

from filesystem_extension import (
    FIRMWARE_EXTENSION_SLOTS,
    FilesystemExtensionTable,
    M3U_LIBRARY_MASK,
    MUSIC_LIBRARY_MASK,
    driver_slot_positions,
)


class FilesystemExtensionTableTests(unittest.TestCase):
    def setUp(self) -> None:
        self.table = FilesystemExtensionTable()

    def test_table_matches_the_recovered_31_slot_vocabulary(self):
        self.assertEqual(len(FIRMWARE_EXTENSION_SLOTS), 31)
        self.assertEqual(self.table.slot_for_extension("MP3"), 0)
        self.assertEqual(self.table.slot_for_extension("JPEG"), 9)
        self.assertEqual(self.table.slot_for_extension("*f"), 29)

    def test_extension_lookup_is_case_insensitive(self):
        self.assertEqual(self.table.slot_for_extension("flac"), 4)
        self.assertIsNone(self.table.slot_for_extension("FLACX"))

    def test_acceptance_requires_explicit_slot_selection(self):
        self.assertTrue(self.table.accepts_slot("mp3", {0}))
        self.assertFalse(self.table.accepts_slot("mp3", set()))
        self.assertFalse(self.table.accepts_slot("bin", {0}))

    def test_driver_mask_uses_high_bit_first_slot_order(self):
        self.assertEqual(driver_slot_positions((1 << 31) | (1 << 1) | 1), (0, 30))

    def test_library_masks_expose_driver_slots_without_relabeling_the_masks(self):
        self.assertEqual(
            MUSIC_LIBRARY_MASK.driver_slot_positions,
            (1, 2, 3, 4, 5, 6, 11, 12, 15, 20, 21, 22, 24, 26),
        )
        self.assertEqual(M3U_LIBRARY_MASK.driver_slot_positions, (12, 21))


if __name__ == "__main__":
    unittest.main()
