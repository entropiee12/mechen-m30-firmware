import unittest

from listbox import (
    DIRECTORY_HEAD_X,
    DIRECTORY_ROW_HEIGHT,
    DIRECTORY_SCROLL_PERIOD_MS,
    DIRECTORY_TEXT_WIDTH,
    M30_DIRECTORY_STYLE,
    active_redraw_period_ms,
    EXPERIMENTAL_NAME_BUFFER_BYTES,
    INFERRED_LIST_BUFFER_BYTES,
    LIST_BUFFER_ADDR,
    LIST_SLOT_COUNT,
    MARQUEE_SCAN_BYTES,
    MAX_NON_OVERLAPPING_NAME_BYTES,
    PATH_INFO_ADDR,
    experimental_name_layout,
    firmware_name_capacity_bytes,
    assumed_name_payload_bytes,
    assumed_directory_slot_text,
    format_directory_83_name,
    marquee_frame,
    needs_active_scroll,
    prepare_marquee_utf16,
    scroll_window,
)


class DirectoryListboxTests(unittest.TestCase):
    def test_m30_geometry_keeps_icon_and_text_separate(self):
        self.assertEqual(M30_DIRECTORY_STYLE.head_x, DIRECTORY_HEAD_X)
        self.assertEqual(M30_DIRECTORY_STYLE.text_width, DIRECTORY_TEXT_WIDTH)
        self.assertEqual(M30_DIRECTORY_STYLE.row_height, DIRECTORY_ROW_HEIGHT)

    def test_long_folder_name_uses_active_scroll_contract(self):
        self.assertFalse(needs_active_scroll(len("short")))
        self.assertTrue(needs_active_scroll(DIRECTORY_TEXT_WIDTH + 1))
        self.assertEqual(DIRECTORY_SCROLL_PERIOD_MS, 50)
        self.assertEqual(active_redraw_period_ms(), DIRECTORY_SCROLL_PERIOD_MS)

    def test_directory_destination_slot_has_observed_byte_length(self):
        # The SDK says 52, but M30's browser binary uses 0x32 = 50 bytes.
        self.assertEqual(firmware_name_capacity_bytes(), 50)

    def test_bom_slot_hypothesis_truncates_before_marquee(self):
        self.assertEqual(assumed_name_payload_bytes(), 46)
        prepared = assumed_directory_slot_text("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        self.assertEqual(prepared, "ABCDEFGHIJKLMNOPQRSTUVW")
        self.assertLessEqual(len(prepared.encode("utf-16le")) + 4, firmware_name_capacity_bytes())

    def test_bom_slot_hypothesis_does_not_split_non_bmp_character(self):
        prepared = assumed_directory_slot_text("A" * 22 + "😀B")
        self.assertEqual(prepared, "A" * 22)

    def test_directory_83_name_conversion_matches_the_runtime_boundary(self):
        converted = format_directory_83_name(b"FILE    TXT\0")

        self.assertEqual(converted[:9], b"FILE.TXT\0")
        self.assertEqual(converted, b"FILE.TXT\0TXT")

    def test_directory_83_name_keeps_bom_prefixed_payload_unchanged(self):
        raw_name = b"\xff\xfe\x41\x00\x00\x00\x00\x00\x00\x00\x00\x00"

        self.assertEqual(format_directory_83_name(raw_name), raw_name)

    def test_experimental_64_byte_layout_fits_the_page_but_overlaps_path_info(self):
        slot_bytes, used = experimental_name_layout()
        self.assertEqual(slot_bytes, EXPERIMENTAL_NAME_BUFFER_BYTES)
        self.assertEqual(used, LIST_SLOT_COUNT * EXPERIMENTAL_NAME_BUFFER_BYTES)
        self.assertEqual(used, INFERRED_LIST_BUFFER_BYTES)
        self.assertEqual(MAX_NON_OVERLAPPING_NAME_BYTES, 50)
        self.assertGreater(LIST_BUFFER_ADDR + used, PATH_INFO_ADDR)

    def test_scroll_wraps_with_two_space_gap(self):
        self.assertEqual(scroll_window("abcdefghij", 0, 5), "abcde")
        self.assertEqual(scroll_window("abcdefghij", 8, 5), "ij  a")

    def test_short_names_are_not_modified(self):
        self.assertEqual(scroll_window("folder", 37, 20), "folder")

    def test_marquee_advances_by_pixels_and_restores_background_tail(self):
        first = marquee_frame("A long folder name", 0)
        next_frame = marquee_frame("A long folder name", 1)
        self.assertEqual(first.offset_px, 0)
        self.assertEqual(next_frame.offset_px, 1)
        self.assertEqual(first.clear_width_px, 1)
        self.assertEqual(next_frame.clear_width_px, 1)
        self.assertEqual(first.restore_mode, "sdimage-tail")
        self.assertEqual(first.background, "selection-red")

    def test_marquee_wraps_offsets_without_stale_background(self):
        frame = marquee_frame("abc", 10_000, viewport_width_px=32)
        self.assertLess(frame.offset_px, len("abc  ") * M30_DIRECTORY_STYLE.glyph_width_px)
        self.assertEqual(frame.clear_width_px, 1)
        self.assertEqual(frame.restore_mode, "sdimage-tail")

    def test_marquee_preparation_skips_bom_and_adds_two_spaces(self):
        source = b"\xff\xfe" + "folder".encode("utf-16le") + b"\x00\x00"

        prepared = prepare_marquee_utf16(source)

        self.assertEqual(prepared, "folder  ".encode("utf-16le") + b"\x00\x00")

    def test_marquee_preparation_accepts_terminator_inside_102_byte_scan(self):
        source = b"\xff\xfe" + ("A" * 49).encode("utf-16le") + b"\x00\x00"

        self.assertEqual(len(source), MARQUEE_SCAN_BYTES)
        self.assertTrue(prepare_marquee_utf16(source).startswith(source[2:-2]))

    def test_marquee_preparation_rejects_terminator_after_102_byte_scan(self):
        source = b"\xff\xfe" + ("A" * 50).encode("utf-16le") + b"\x00\x00"

        with self.assertRaises(ValueError):
            prepare_marquee_utf16(source)


if __name__ == "__main__":
    unittest.main()
