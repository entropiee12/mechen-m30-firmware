import unittest

from text_alignment import (
    centered_x,
    first_line,
    measure_centered_text,
    true_length_px,
)


class TextAlignmentTests(unittest.TestCase):
    def test_line_break_limits_true_length_before_centering(self):
        text = "Confirm upgrade file:\n[%s]"

        measurement = measure_centered_text(text, region_x=20, region_width_px=240)

        self.assertEqual(measurement.first_line, "Confirm upgrade file:")
        self.assertEqual(measurement.true_length_px, len("Confirm upgrade file:") * 8)
        self.assertEqual(measurement.centered_x, 56)

    def test_crlf_is_one_line_break(self):
        self.assertEqual(first_line("first\r\nsecond"), "first")
        self.assertEqual(true_length_px("first\r\nsecond"), 5 * 8)

    def test_without_line_break_the_full_text_is_measured(self):
        text = "Confirm upgrade file: [%s]"

        self.assertEqual(
            true_length_px(text),
            len(text) * 8,
        )

    def test_centering_keeps_region_origin(self):
        self.assertEqual(centered_x(35, 205, 80), 97)

    def test_invalid_measurement_arguments_are_rejected(self):
        with self.assertRaises(ValueError):
            true_length_px("text", glyph_width_px=0)

        with self.assertRaises(ValueError):
            centered_x(0, 0, 10)

        with self.assertRaises(ValueError):
            centered_x(0, 100, -1)


if __name__ == "__main__":
    unittest.main()
