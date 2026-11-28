import struct
import unittest

from library_order_probe import compare_candidate_orders


def make_record(title: bytes, album: bytes, artist: bytes, genre: bytes) -> bytes:
    record = bytearray(0x100)
    record[0x00 : 0x00 + len(title)] = title
    record[0x40 : 0x40 + len(artist)] = artist
    record[0x58 : 0x58 + len(album)] = album
    record[0x88 : 0x88 + len(genre)] = genre
    struct.pack_into("<b", record, 0xF2, 0)

    return bytes(record)


class LibraryOrderProbeTests(unittest.TestCase):
    def test_candidate_uses_observed_previous_section_rank(self):
        records = (
            make_record(b"Beta\0", b"Album B\0", b"Artist\0", b"Genre\0"),
            make_record(b"Alpha\0", b"Album A\0", b"Artist\0", b"Genre\0"),
            make_record(b"Alpha\0", b"Album C\0", b"Artist\0", b"Genre\0"),
        )
        orders = {
            "ALLSONG": (1, 2, 0),
            "ALBUM": (1, 0, 2),
            "ARTIST": (1, 0, 2),
            "GENRE": (1, 0, 2),
        }

        summary = compare_candidate_orders(records, orders)

        for section in summary.values():
            self.assertEqual(section["matching_entries"], 3)
            self.assertIsNone(section["first_mismatch"])

    def test_rejects_an_order_with_a_missing_record(self):
        records = (
            make_record(b"Alpha\0", b"Album\0", b"Artist\0", b"Genre\0"),
        )
        orders = {
            "ALLSONG": (),
            "ALBUM": (),
            "ARTIST": (),
            "GENRE": (),
        }

        with self.assertRaises(ValueError):
            compare_candidate_orders(records, orders)


if __name__ == "__main__":
    unittest.main()
