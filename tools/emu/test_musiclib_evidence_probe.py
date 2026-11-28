import unittest
import struct

from musiclib_evidence_probe import inspect_music_library, _summarize_record_links


def make_empty_library() -> bytes:
    data = bytearray(512)
    for name, offset in (
        ("ALLSONG", 0x10),
        ("ALBUM", 0x2C),
        ("ARTIST", 0x48),
        ("GENRE", 0x64),
    ):
        data[offset : offset + len(name)] = name.encode("ascii")

    return bytes(data)


def make_library_with_record_links(links: tuple[tuple[int, int], ...]) -> bytes:
    record_count = len(links)
    record_area_size = ((record_count * 0x100 + 511) // 512) * 512
    data = bytearray(512 + record_area_size)
    struct.pack_into("<H", data, 8, record_count)

    for name, offset in (
        ("ALLSONG", 0x10),
        ("ALBUM", 0x2C),
        ("ARTIST", 0x48),
        ("GENRE", 0x64),
    ):
        data[offset : offset + len(name)] = name.encode("ascii")

    for index, (previous_index, next_index) in enumerate(links):
        record_offset = 512 + index * 0x100
        struct.pack_into("<HH", data, record_offset + 0xFC, previous_index, next_index)

    return bytes(data)


class MusicLibraryEvidenceProbeTests(unittest.TestCase):
    def test_accepts_a_sector_aligned_empty_library_with_any_hash(self):
        summary = inspect_music_library(
            make_empty_library(),
            expected_sha256=None,
        )

        self.assertEqual(summary["bytes"], 512)
        self.assertEqual(summary["record_count"], 0)
        self.assertEqual(summary["record_area_sectors"], 0)
        self.assertEqual(summary["location_key_samples"], ())
        self.assertEqual(summary["location_lookup_samples"], ())
        self.assertEqual(summary["record_link_samples"], ())
        self.assertEqual(
            summary["record_link_summary"],
            {
                "previous_linear_matches": 0,
                "next_one_past_matches": 0,
                "out_of_range_values": 0,
            },
        )
        self.assertEqual(summary["sections"]["ALBUM"]["span_count"], 0)

    def test_reports_record_link_values_without_rejecting_terminal_candidates(self):
        image = make_library_with_record_links(((0xFFFF, 1), (0, 2), (1, 3)))
        samples, link_summary = _summarize_record_links(image, 3)

        self.assertEqual(
            samples,
            (
                {"index": 0, "previous_index": 0xFFFF, "next_index": 1},
                {"index": 2, "previous_index": 1, "next_index": 3},
            ),
        )
        self.assertEqual(
            link_summary,
            {
                "previous_linear_matches": 3,
                "next_one_past_matches": 3,
                "out_of_range_values": 2,
            },
        )

    def test_rejects_a_non_sector_aligned_image(self):
        with self.assertRaises(ValueError):
            inspect_music_library(bytes(511), expected_sha256=None)

    def test_rejects_a_hash_mismatch_before_interpreting_the_image(self):
        with self.assertRaises(ValueError):
            inspect_music_library(make_empty_library(), expected_sha256="0" * 64)


if __name__ == "__main__":
    unittest.main()
