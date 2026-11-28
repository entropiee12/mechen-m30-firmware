import struct
import unittest

from library_index_sort import (
    INDEX_TEXT_CONVERSION_REPLACE_CONTINUE_STALE_TAIL_PREFIX,
    INDEX_TEXT_CONVERSION_CANDIDATE_TERMINAL_OOR_ZERO_PREFIX,
    INDEX_TEXT_CONVERSION_CANDIDATE_TERMINAL_OOR_ZERO_STALE_TAIL_PREFIX,
    INDEX_TEXT_CONVERSION_STOP_ZERO,
    _select_index_text_converter,
    derive_section_run_lengths,
    deepest_group_indices_from_runs,
    group_indices_from_runs,
    refine_sort_record_indices,
    sort_record_indices,
)


def make_record(
    *,
    title: bytes = b"",
    album: bytes = b"",
    artist: bytes = b"",
    genre: bytes = b"",
    track: int = 0,
) -> bytes:
    record = bytearray(0x100)
    record[0x00 : 0x00 + len(title)] = title
    record[0x40 : 0x40 + len(artist)] = artist
    record[0x58 : 0x58 + len(album)] = album
    record[0x88 : 0x88 + len(genre)] = genre
    struct.pack_into("<b", record, 0xF2, track)
    return bytes(record)


class LibraryIndexSortTests(unittest.TestCase):
    def test_all_song_sort_is_ascii_case_insensitive_and_stable(self):
        records = (
            make_record(title=b"beta\0"),
            make_record(title=b"ALPHA\0"),
            make_record(title=b"alpha\0"),
        )

        self.assertEqual(sort_record_indices(records, "ALLSONG"), (1, 2, 0))

    def test_album_sort_uses_signed_track_byte_before_previous_rank(self):
        records = (
            make_record(album=b"Album\0", track=0),
            make_record(album=b"album\0", track=-1),
            make_record(album=b"Other\0", track=0),
        )

        self.assertEqual(
            sort_record_indices(records, "ALBUM", previous_rank=(2, 1, 0)),
            (1, 0, 2),
        )

    def test_refined_sort_reloads_equal_short_keys(self):
        records = (
            make_record(title=b"ABCDEFGHIJKLMNOPZ\0"),
            make_record(title=b"ABCDEFGHIJKLMNOPA\0"),
        )

        self.assertEqual(sort_record_indices(records, "ALLSONG"), (1, 0))
        self.assertEqual(refine_sort_record_indices(records, "ALLSONG"), (1, 0))

        reversed_records = tuple(reversed(records))
        self.assertEqual(sort_record_indices(reversed_records, "ALLSONG"), (0, 1))
        self.assertEqual(refine_sort_record_indices(reversed_records, "ALLSONG"), (0, 1))

    def test_refined_sort_matches_stock_unstable_equal_key_order(self):
        records = tuple(make_record(title=b"Same\0") for _ in range(10))

        self.assertEqual(
            refine_sort_record_indices(records, "ALLSONG"),
            (5, 1, 2, 3, 4, 0, 6, 7, 8, 9),
        )

    def test_stop_zero_conversion_is_explicitly_opt_in(self):
        records = (
            make_record(
                title=b"\xff\xfe" + "03. Şiraze".encode("utf-16-le")
            ),
            make_record(title=b"03. '94 Ghost\0"),
        )

        self.assertEqual(refine_sort_record_indices(records, "ALLSONG"), (1, 0))
        self.assertEqual(
            refine_sort_record_indices(
                records,
                "ALLSONG",
                conversion_mode=INDEX_TEXT_CONVERSION_STOP_ZERO,
            ),
            (0, 1),
        )

    def test_replace_continue_conversion_is_experimental_and_keeps_suffix(self):
        record = make_record(
            title=b"\xff\xfe" + "AıB".encode("utf-16-le") + b"\0\0"
        )
        convert = _select_index_text_converter("replace-continue:20")

        self.assertEqual(convert(record, 0, 0x40)[:5], b"A B\0\0")

    def test_terminal_oor_zero_conversion_replaces_middle_and_terminates_at_end(self):
        middle_unsupported = make_record(
            title=b"\xff\xfe" + "AıB\0".encode("utf-16-le")
        )
        terminal_unsupported = make_record(
            title=b"\xff\xfe" + "Indelible’\0".encode("utf-16-le")
        )
        conversion_mode = f"{INDEX_TEXT_CONVERSION_CANDIDATE_TERMINAL_OOR_ZERO_PREFIX}01"
        convert = _select_index_text_converter(conversion_mode)

        self.assertEqual(convert(middle_unsupported, 0, 0x40)[:5], b"A\x01B\0\0")
        self.assertEqual(
            convert(terminal_unsupported, 0, 0x40)[:12],
            b"Indelible\0\0\0",
        )

    def test_terminal_oor_zero_stale_tail_preserves_bytes_after_terminator(self):
        record = make_record(
            title=b"\xff\xfe" + "Aı\0".encode("utf-16-le") + b"XY"
        )
        conversion_mode = (
            f"{INDEX_TEXT_CONVERSION_CANDIDATE_TERMINAL_OOR_ZERO_STALE_TAIL_PREFIX}01"
        )
        convert = _select_index_text_converter(conversion_mode)

        self.assertEqual(convert(record, 0, 0x40)[:6], b"A\0\x31\x01\0\0")

    def test_replace_continue_conversion_rejects_invalid_replacement_byte(self):
        with self.assertRaises(ValueError):
            _select_index_text_converter("replace-continue:100")

    def test_replace_continue_stale_tail_retains_source_bytes_after_nul(self):
        record = make_record(title=b"\xff\xfeA\x00\x00\x00XY")
        conversion_mode = f"{INDEX_TEXT_CONVERSION_REPLACE_CONTINUE_STALE_TAIL_PREFIX}01"
        convert = _select_index_text_converter(conversion_mode)

        self.assertEqual(convert(record, 0, 0x40)[:6], b"A\x00\x00\x00XY")

    def test_artist_grouping_can_use_stop_zero_after_replacement_sort(self):
        utf16_artist = "Indelible’\0".encode("utf-16-le")
        plain_artist = "Indelible\0".encode("utf-16-le")
        records = (
            make_record(
                album=b"Album\0",
                artist=b"\xff\xfe" + utf16_artist,
            ),
            make_record(
                album=b"Album\0",
                artist=b"\xff\xfe" + plain_artist,
            ),
        )
        replacement_mode = f"{INDEX_TEXT_CONVERSION_REPLACE_CONTINUE_STALE_TAIL_PREFIX}01"
        order = refine_sort_record_indices(
            records,
            "ARTIST",
            previous_rank=(0, 1),
            conversion_mode=replacement_mode,
        )

        self.assertEqual(order, (1, 0))
        self.assertEqual(
            derive_section_run_lengths(
                records,
                "ARTIST",
                order,
                album_group_indices=(0, 0),
                conversion_mode=INDEX_TEXT_CONVERSION_STOP_ZERO,
            ),
            ((2,), (2,)),
        )

    def test_non_first_section_requires_previous_rank(self):
        records = (make_record(artist=b"Artist\0"),)

        with self.assertRaises(ValueError):
            sort_record_indices(records, "ARTIST")

    def test_rejects_non_record_sized_input(self):
        with self.assertRaises(ValueError):
            sort_record_indices((bytes(0xFF),), "ALLSONG")

    def test_derives_nested_artist_and_genre_runs(self):
        records = (
            make_record(album=b"Album A\0", artist=b"Artist A\0", genre=b"Genre\0"),
            make_record(album=b"Album B\0", artist=b"Artist A\0", genre=b"Genre\0"),
            make_record(album=b"Album C\0", artist=b"Artist B\0", genre=b"Genre\0"),
            make_record(album=b"Album C\0", artist=b"Artist B\0", genre=b"Genre\0"),
        )
        order = (0, 1, 2, 3)
        album_runs = derive_section_run_lengths(records, "ALBUM", order)
        album_groups = group_indices_from_runs(
            order,
            album_runs[0],
        )
        artist_runs = derive_section_run_lengths(
            records,
            "ARTIST",
            order,
            album_group_indices=album_groups,
        )
        artist_groups = group_indices_from_runs(order, artist_runs[0])

        self.assertEqual(
            deepest_group_indices_from_runs(order, album_runs),
            album_groups,
        )
        self.assertEqual(artist_runs, ((2, 2), (1, 1, 2)))
        self.assertEqual(
            deepest_group_indices_from_runs(order, artist_runs),
            (0, 1, 2, 2),
        )
        self.assertEqual(
            derive_section_run_lengths(
                records,
                "GENRE",
                order,
                album_group_indices=album_groups,
                artist_group_indices=artist_groups,
            ),
            ((4,), (2, 2), (1, 1, 2)),
        )

    def test_group_runs_require_previous_section_groups(self):
        records = (make_record(artist=b"Artist\0"),)

        with self.assertRaises(ValueError):
            derive_section_run_lengths(records, "ARTIST", (0,))

    def test_group_indices_reject_non_permutation_order(self):
        with self.assertRaises(ValueError):
            group_indices_from_runs((0, 0), (2,))


if __name__ == "__main__":
    unittest.main()
