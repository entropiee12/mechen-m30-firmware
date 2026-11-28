import unittest

from library_tag_reader import LibraryTagReaderType, tag_reader_type


class LibraryTagReaderTests(unittest.TestCase):
    def test_grouped_extensions_share_the_recovered_reader(self):
        self.assertEqual(tag_reader_type("mP3"), LibraryTagReaderType.MPEG)
        self.assertEqual(tag_reader_type("M4A"), LibraryTagReaderType.M4A_FAMILY)
        self.assertEqual(tag_reader_type("aax"), LibraryTagReaderType.M4A_FAMILY)
        self.assertEqual(tag_reader_type("wmv"), LibraryTagReaderType.WINDOWS_AUDIO)

    def test_single_format_reader_types_are_preserved(self):
        self.assertEqual(tag_reader_type("AA"), LibraryTagReaderType.AA)
        self.assertEqual(tag_reader_type("AAC"), LibraryTagReaderType.AAC)
        self.assertEqual(tag_reader_type("APE"), LibraryTagReaderType.APE)
        self.assertEqual(tag_reader_type("FLAC"), LibraryTagReaderType.FLAC)
        self.assertEqual(tag_reader_type("OGG"), LibraryTagReaderType.OGG)

    def test_unknown_extension_uses_type_eight_fallback(self):
        self.assertEqual(tag_reader_type("TXT"), LibraryTagReaderType.FALLBACK)
        self.assertEqual(tag_reader_type("unknown"), LibraryTagReaderType.FALLBACK)


if __name__ == "__main__":
    unittest.main()
