import unittest

from artwork_cache import AlbumPicRecord, decode_album_pic


class ArtworkCacheTests(unittest.TestCase):
    def test_ok_record_round_trips_the_six_byte_header_and_rgb565_payload(self):
        pixels = bytes(range(16))
        record = AlbumPicRecord("OK", 2, 3, 4, 2, pixels)

        encoded = record.encode()
        decoded = decode_album_pic(encoded)

        self.assertEqual(encoded[:6], b"OK\x02\x03\x04\x02")
        self.assertEqual(decoded, record)

    def test_no_record_has_zero_geometry_and_no_payload(self):
        record = AlbumPicRecord("NO", 0, 0, 0, 0, b"")

        self.assertEqual(record.encode(), b"NO\0\0\0\0")
        self.assertEqual(decode_album_pic(record.encode()), record)

    def test_payload_size_and_status_are_validated(self):
        with self.assertRaises(ValueError):
            AlbumPicRecord("OK", 0, 0, 2, 2, b"short")
        with self.assertRaises(ValueError):
            decode_album_pic(b"XX\0\0\0\0")


if __name__ == "__main__":
    unittest.main()
