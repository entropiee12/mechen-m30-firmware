import struct
import unittest

from library_record_codec import (
    RECORD_SIZE,
    TRACK_SENTINEL,
    TRACK_UNSPECIFIED,
    decode_record,
    parse_track_number,
)


class LibraryRecordCodecTests(unittest.TestCase):
    def test_decodes_bom_text_and_fixed_record_fields(self):
        data = bytearray(RECORD_SIZE)
        data[0x00:0x40] = b"\xff\xfeT\x00i\x00t\x00l\x00e\x00\0\0".ljust(0x40, b"\0")
        data[0x40:0x58] = b"Artist\0".ljust(0x18, b"\0")
        data[0x58:0x88] = b"Album\0".ljust(0x30, b"\0")
        data[0x88:0xA0] = b"Genre\0".ljust(0x18, b"\0")
        data[0xA0:0xA3] = b"07\0"
        data[0xA3] = 1
        data[0xA4:0xA8] = b"MP3\0"
        data[0xA8] = 1
        data[0xA9:0xF2] = bytes(range(0x49))
        struct.pack_into("<H", data, 0xF2, 7)
        struct.pack_into("<II", data, 0xF4, 0x00134D9F, 0x80)
        struct.pack_into("<HH", data, 0xFC, 3, 5)

        record = decode_record(bytes(data))

        self.assertEqual(record.title, "Title")
        self.assertEqual(record.artist, "Artist")
        self.assertEqual(record.track_text, "07")
        self.assertTrue(record.tag_ok)
        self.assertEqual(record.extension, "MP3")
        self.assertEqual(record.track_number, 7)
        self.assertEqual(record.always_one, 1)
        self.assertEqual(record.location_primary, bytes(range(0x49)))
        self.assertEqual(record.previous_index, 3)
        self.assertEqual(record.next_index, 5)
        self.assertEqual(record.location_secondary, b"\x9fM\x13\0\x80\0\0\0")
        self.assertEqual(record.cluster_no, 0x00134D9F)
        self.assertEqual(record.dir_entry, 0x80)

    def test_opaque_location_and_tail_values_are_exposed_without_validation(self):
        data = bytearray(RECORD_SIZE)
        data[0xA9:0xF2] = bytes((index * 7) & 0xFF for index in range(0x49))
        data[0xF4:0xFC] = b"\x00\xff\x10\xef\x80\x01\x7f\xaa"
        struct.pack_into("<HH", data, 0xFC, 0xBEEF, 0xCAFE)

        record = decode_record(bytes(data))

        self.assertEqual(
            record.location_primary,
            bytes((index * 7) & 0xFF for index in range(0x49)),
        )
        self.assertEqual(record.location_secondary, b"\x00\xff\x10\xef\x80\x01\x7f\xaa")
        self.assertEqual(record.cluster_no, 0xEF10FF00)
        self.assertEqual(record.dir_entry, 0xAA7F0180)
        self.assertEqual(record.previous_index, 0xBEEF)
        self.assertEqual(record.next_index, 0xCAFE)

    def test_requires_one_complete_record(self):
        with self.assertRaises(ValueError):
            decode_record(bytes(RECORD_SIZE - 1))

    def test_track_parser_reads_at_most_four_digits(self):
        self.assertEqual(
            parse_track_number("0012/Title", tag_ok=True, existing_track_number=0),
            12,
        )
        self.assertEqual(
            parse_track_number("12345", tag_ok=True, existing_track_number=0),
            1234,
        )

    def test_track_parser_preserves_or_marks_unspecified_values(self):
        self.assertEqual(
            parse_track_number("99", tag_ok=True, existing_track_number=7),
            7,
        )
        self.assertEqual(
            parse_track_number("Title", tag_ok=True, existing_track_number=TRACK_SENTINEL),
            TRACK_UNSPECIFIED,
        )
        self.assertEqual(
            parse_track_number("12", tag_ok=False, existing_track_number=7),
            0,
        )


if __name__ == "__main__":
    unittest.main()
