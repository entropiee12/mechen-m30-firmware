import unittest

from library_record import LibraryFallbackRecord, format_short_name


class LibraryFallbackRecordTests(unittest.TestCase):
    def test_fallback_uses_short_name_and_fixed_metadata(self):
        record = LibraryFallbackRecord.from_short_name("TRACK~1.MP3")
        self.assertEqual(record.title, "TRACK~1.MP3")
        self.assertEqual(record.artist, "No Artist")
        self.assertEqual(record.album, "No Album")
        self.assertEqual(record.genre, "No Genre")
        self.assertFalse(record.tag_ok)

    def test_fallback_rejects_an_empty_name(self):
        with self.assertRaises(ValueError):
            LibraryFallbackRecord.from_short_name("")

    def test_formats_the_raw_eight_dot_three_name(self):
        self.assertEqual(format_short_name(b"TRACK   MP3"), "TRACK.MP3")
        self.assertEqual(format_short_name(b"README     "), "README")
        self.assertEqual(
            LibraryFallbackRecord.from_raw_short_name(b"TRACK   MP3").title,
            "TRACK.MP3",
        )

    def test_short_name_buffer_requires_eleven_bytes(self):
        with self.assertRaises(ValueError):
            format_short_name(b"SHORT")


if __name__ == "__main__":
    unittest.main()
