import unittest

from browser_row_record import (
    ROW_RECORD_SIZE,
    browser_row_record_offset,
    decode_browser_row_record,
)


class BrowserRowRecordTests(unittest.TestCase):
    def test_exposes_only_the_verified_converter_field(self):
        data = bytes(range(0xA0, 0xA0 + ROW_RECORD_SIZE))

        record = decode_browser_row_record(data)

        self.assertEqual(record.converter_source_halfword, 0xA3A2)
        self.assertEqual(record.raw, data)

    def test_other_bytes_remain_opaque(self):
        first = bytearray(ROW_RECORD_SIZE)
        second = bytearray(first)
        second[0] = 0x7F
        second[4] = 0x80

        first_record = decode_browser_row_record(bytes(first))
        second_record = decode_browser_row_record(bytes(second))

        self.assertEqual(first_record.converter_source_halfword, 0)
        self.assertEqual(second_record.converter_source_halfword, 0)
        self.assertNotEqual(first_record.raw, second_record.raw)

    def test_uses_the_verified_list_state_stride(self):
        self.assertEqual(browser_row_record_offset(0), 0x04)
        self.assertEqual(browser_row_record_offset(2), 0x2C)
        self.assertEqual(browser_row_record_offset(7), 0x90)

    def test_rejects_incomplete_records_and_negative_rows(self):
        with self.assertRaises(ValueError):
            decode_browser_row_record(bytes(ROW_RECORD_SIZE - 1))

        with self.assertRaises(ValueError):
            browser_row_record_offset(-1)


if __name__ == "__main__":
    unittest.main()
