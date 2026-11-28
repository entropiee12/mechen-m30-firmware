import struct
import unittest

from library_primary_location_probe import inspect_primary_location


def make_library(records: list[tuple[int, int, int]]) -> bytes:
    data = bytearray(0x200 + len(records) * 0x100)
    struct.pack_into("<H", data, 0x08, len(records))
    for index, (cluster_no, word6, word8) in enumerate(records):
        start = 0x200 + index * 0x100
        struct.pack_into("<I", data, start + 0xF4, cluster_no)
        struct.pack_into("<I", data, start + 0xA9 + 0x18, word6)
        struct.pack_into("<I", data, start + 0xA9 + 0x20, word8)
    return bytes(data)


class LibraryPrimaryLocationProbeTests(unittest.TestCase):
    def test_reports_the_exclusive_cluster_correlations(self):
        summary = inspect_primary_location(
            make_library(
                [
                    (0x100, 0x100, 0x200),
                    (0x200, 0x300, 0x200),
                    (0x300, 0x300, 0x300),
                    (0x400, 0x500, 0x600),
                ]
            )
        )

        self.assertEqual(summary["record_count"], 4)
        self.assertEqual(summary["word6_cluster_matches"], 2)
        self.assertEqual(summary["word8_cluster_matches"], 2)
        self.assertEqual(summary["both_cluster_matches"], 1)
        self.assertEqual(summary["neither_cluster_matches"], 1)
        self.assertEqual(summary["exclusive_cluster_matches"], 2)
        self.assertEqual(summary["companion_delta_non_32_aligned"], 0)

    def test_rejects_an_image_shorter_than_declared_record_area(self):
        data = bytearray(0x200)
        struct.pack_into("<H", data, 0x08, 1)
        with self.assertRaises(ValueError):
            inspect_primary_location(bytes(data))


if __name__ == "__main__":
    unittest.main()
