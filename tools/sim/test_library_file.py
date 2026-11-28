from dataclasses import replace
import struct
import unittest

from sd_block_device import SdCardBlockDevice
from library_file import (
    HEADER_SIZE,
    LibrarySectionIndex,
    LibrarySectionNode,
    LibrarySectionTables,
    RECORD_SIZE,
    SECTION_NODE_SIZE,
    SECTOR_SIZE,
    decode_library_section_node,
    find_library_record_index,
    interpret_library_section_index,
    parse_library_layout,
    read_library_image,
    read_library_layout,
    read_library_record,
    read_library_section_index,
    read_library_section_tables,
    read_library_section_nodes,
    read_library_section_words,
    validate_library_section_tables,
    validate_library_section_node_indices,
    validate_library_section_nodes,
)


def make_library(record_count: int = 3) -> bytearray:
    record_sectors = (record_count * RECORD_SIZE + SECTOR_SIZE - 1) // SECTOR_SIZE
    data = bytearray((1 + record_sectors) * SECTOR_SIZE)
    struct.pack_into("<H", data, 0x08, record_count)

    for name, offset in (("ALLSONG", 0x10), ("ALBUM", 0x2C), ("ARTIST", 0x48), ("GENRE", 0x64)):
        data[offset : offset + len(name)] = name.encode("ascii")

    struct.pack_into("<3H", data, 0x10 + 8, 1, 0, 0)
    struct.pack_into("<7H", data, 0x10 + 14, 3, 0, 0, 4, 5, 0, 0)
    return data


def add_record(data: bytearray, title: bytes, record_index: int = 0) -> None:
    record = bytearray(RECORD_SIZE)
    record[: len(title)] = title
    record[0xA3] = 1
    record[0xA4:0xA8] = b"MP3\0"
    record_offset = HEADER_SIZE + record_index * RECORD_SIZE
    data[record_offset : record_offset + RECORD_SIZE] = record


class MusicLibraryLayoutTests(unittest.TestCase):
    def test_decodes_observed_section_node_fields(self):
        node = struct.pack(
            "<BBHHHHHHH",
            1,
            2,
            1,
            251,
            23,
            0,
            0xFFFF,
            0xFFFF,
            0xFFFF,
        )

        decoded_node = decode_library_section_node(node)

        self.assertEqual(decoded_node.node_type, 1)
        self.assertEqual(decoded_node.depth, 2)
        self.assertEqual(decoded_node.child_count, 1)
        self.assertEqual(decoded_node.song_count, 251)
        self.assertEqual(decoded_node.first_record_index, 23)
        self.assertEqual(decoded_node.first_child_index, 0)
        self.assertEqual(decoded_node.parent_index, 0xFFFF)
        self.assertEqual(decoded_node.previous_index, 0xFFFF)
        self.assertEqual(decoded_node.next_index, 0xFFFF)

    def test_rejects_short_section_node(self):
        with self.assertRaises(ValueError):
            decode_library_section_node(bytes(SECTION_NODE_SIZE - 1))

    def test_reads_and_decodes_one_record_from_sd_bytes(self):
        image = make_library(1)
        add_record(image, b"From SD")
        card = SdCardBlockDevice(sector_count=3)
        card.write_sector(1, image[:SECTOR_SIZE])
        card.write_sector(2, image[SECTOR_SIZE:])

        record = read_library_record(card, 1, 0)

        self.assertEqual(record.title, "From SD")
        self.assertEqual(record.extension, "MP3")

    def test_reads_opaque_record_tail_from_sd_bytes(self):
        image = make_library(2)
        add_record(image, b"From SD", record_index=1)
        image[0x300 + 0xA9 : 0x300 + 0xF2] = bytes(range(0x49))
        image[0x300 + 0xF4 : 0x300 + 0xFC] = b"LOCATION"
        struct.pack_into("<HH", image, 0x300 + 0xFC, 0xBEEF, 0xCAFE)

        card = SdCardBlockDevice(sector_count=3)
        card.write_sector(1, image[:SECTOR_SIZE])
        card.write_sector(2, image[SECTOR_SIZE:])

        record = read_library_record(card, 1, 1)

        self.assertEqual(record.title, "From SD")
        self.assertEqual(record.location_primary, bytes(range(0x49)))
        self.assertEqual(record.location_secondary, b"LOCATION")
        self.assertEqual(record.previous_index, 0xBEEF)
        self.assertEqual(record.next_index, 0xCAFE)

    def test_finds_active_record_by_the_bounded_location_key(self):
        image = make_library(3)
        add_record(image, b"Inactive", record_index=0)
        add_record(image, b"Match", record_index=1)
        image[0x200 + 0xA8] = 0
        image[0x300 + 0xA8] = 1
        image[0x300 + 0xF4 : 0x300 + 0xFC] = b"MATCHKEY"

        card = SdCardBlockDevice(sector_count=4)
        for sector in range(len(image) // SECTOR_SIZE):
            start = sector * SECTOR_SIZE
            card.write_sector(sector, image[start : start + SECTOR_SIZE])

        self.assertIsNone(find_library_record_index(card, 0, b"inactive"))
        self.assertEqual(find_library_record_index(card, 0, b"MATCHKEY"), 1)
        self.assertIsNone(find_library_record_index(card, 0, b"missing!"))

    def test_location_lookup_stops_at_the_firmware_limit(self):
        record_count = 4_001
        image = make_library(record_count)
        add_record(image, b"Beyond limit", record_index=4_000)
        record_offset = HEADER_SIZE + 4_000 * RECORD_SIZE
        image[record_offset + 0xA8] = 1
        image[record_offset + 0xF4 : record_offset + 0xFC] = b"LIMITKEY"

        card = SdCardBlockDevice(sector_count=len(image) // SECTOR_SIZE)
        for sector in range(card.sector_count):
            start = sector * SECTOR_SIZE
            card.write_sector(sector, image[start : start + SECTOR_SIZE])

        self.assertIsNone(find_library_record_index(card, 0, b"LIMITKEY"))

    def test_location_lookup_requires_an_eight_byte_key(self):
        image = make_library(1)
        card = SdCardBlockDevice(sector_count=2)
        card.write_sector(0, image[:SECTOR_SIZE])
        card.write_sector(1, image[SECTOR_SIZE:])

        with self.assertRaises(ValueError):
            find_library_record_index(card, 0, b"short")

    def test_record_index_is_bounded_by_the_library_header(self):
        image = make_library(1)
        card = SdCardBlockDevice(sector_count=3)
        card.write_sector(1, image[:SECTOR_SIZE])
        card.write_sector(2, image[SECTOR_SIZE:])

        with self.assertRaises(IndexError):
            read_library_record(card, 1, 1)

    def test_reads_raw_section_words_from_the_header_reference(self):
        image = make_library(3)
        card = SdCardBlockDevice(sector_count=6)
        card.write_sector(1, image[:SECTOR_SIZE])
        card.write_sector(2, image[SECTOR_SIZE : 2 * SECTOR_SIZE])
        card.write_sector(3, image[2 * SECTOR_SIZE : 3 * SECTOR_SIZE])
        card.write_sector(4, struct.pack("<3H", 9, 4, 1).ljust(SECTOR_SIZE, b"\0"))

        words = read_library_section_words(card, 1, "ALLSONG", 0)

        self.assertEqual(words, (9, 4, 1))

    def test_reads_section_tables_with_the_recovered_roles(self):
        image = make_library(3)
        card = SdCardBlockDevice(sector_count=7)
        card.write_sector(1, image[:SECTOR_SIZE])
        card.write_sector(2, image[SECTOR_SIZE : 2 * SECTOR_SIZE])
        card.write_sector(3, image[2 * SECTOR_SIZE : 3 * SECTOR_SIZE])
        card.write_sector(5, struct.pack("<3H", 2, 0, 1).ljust(SECTOR_SIZE, b"\0"))
        card.write_sector(6, struct.pack("<3H", 1, 2, 0).ljust(SECTOR_SIZE, b"\0"))

        tables = read_library_section_tables(card, 1, "ALLSONG")

        self.assertEqual(tables.order, (2, 0, 1))
        self.assertEqual(tables.inverse, (1, 2, 0))
        self.assertEqual(tables.node_indices, ())

    def test_reads_typed_section_index_snapshot(self):
        image = make_library(3)
        card = SdCardBlockDevice(sector_count=7)
        card.write_sector(1, image[:SECTOR_SIZE])
        card.write_sector(2, image[SECTOR_SIZE : 2 * SECTOR_SIZE])
        card.write_sector(3, image[2 * SECTOR_SIZE : 3 * SECTOR_SIZE])
        card.write_sector(
            4,
            struct.pack(
                "<BBHHHHHHH",
                1,
                0,
                0,
                3,
                2,
                0xFFFF,
                0xFFFF,
                0xFFFF,
                0xFFFF,
            ).ljust(SECTOR_SIZE, b"\0"),
        )
        card.write_sector(5, struct.pack("<3H", 2, 0, 1).ljust(SECTOR_SIZE, b"\0"))
        card.write_sector(6, struct.pack("<3H", 1, 2, 0).ljust(SECTOR_SIZE, b"\0"))

        index = read_library_section_index(card, 1, "ALLSONG")

        self.assertEqual(len(index.nodes_by_level), 3)
        self.assertEqual(index.nodes_by_level[0][0].first_record_index, 2)
        self.assertEqual(index.nodes_by_level[1], ())
        self.assertEqual(index.tables.order, (2, 0, 1))

    def test_validates_order_and_inverse_tables(self):
        image = make_library(3)
        card = SdCardBlockDevice(sector_count=7)
        card.write_sector(1, image[:SECTOR_SIZE])
        card.write_sector(2, image[SECTOR_SIZE : 2 * SECTOR_SIZE])
        card.write_sector(3, image[2 * SECTOR_SIZE : 3 * SECTOR_SIZE])
        card.write_sector(5, struct.pack("<3H", 2, 0, 1).ljust(SECTOR_SIZE, b"\0"))
        card.write_sector(6, struct.pack("<3H", 1, 2, 0).ljust(SECTOR_SIZE, b"\0"))

        tables = read_library_section_tables(card, 1, "ALLSONG")

        validate_library_section_tables(tables, 3)

        with self.assertRaises(ValueError):
            validate_library_section_tables(
                LibrarySectionTables(
                    order=tables.order,
                    inverse=(0, 1, 2),
                    node_indices=tables.node_indices,
                ),
                3,
            )

    def test_validates_section_node_links_and_coverage(self):
        nodes = (
            (
                LibrarySectionNode(
                    node_type=1,
                    depth=2,
                    child_count=2,
                    song_count=3,
                    first_record_index=0,
                    first_child_index=0,
                    parent_index=0xFFFF,
                    previous_index=0xFFFF,
                    next_index=1,
                ),
                LibrarySectionNode(
                    node_type=1,
                    depth=2,
                    child_count=1,
                    song_count=2,
                    first_record_index=3,
                    first_child_index=2,
                    parent_index=0xFFFF,
                    previous_index=0,
                    next_index=0xFFFF,
                ),
            ),
            (
                LibrarySectionNode(
                    node_type=1,
                    depth=1,
                    child_count=0,
                    song_count=1,
                    first_record_index=0,
                    first_child_index=0xFFFF,
                    parent_index=0,
                    previous_index=0xFFFF,
                    next_index=1,
                ),
                LibrarySectionNode(
                    node_type=1,
                    depth=1,
                    child_count=0,
                    song_count=2,
                    first_record_index=1,
                    first_child_index=0xFFFF,
                    parent_index=0,
                    previous_index=0,
                    next_index=2,
                ),
                LibrarySectionNode(
                    node_type=1,
                    depth=1,
                    child_count=0,
                    song_count=2,
                    first_record_index=3,
                    first_child_index=0xFFFF,
                    parent_index=1,
                    previous_index=1,
                    next_index=0xFFFF,
                ),
            ),
            (),
        )

        validate_library_section_nodes(nodes, 5)

        invalid_depth_nodes = tuple(
            tuple(replace(node, depth=node.depth + 7) for node in level)
            for level in nodes
        )

        with self.assertRaises(ValueError):
            validate_library_section_nodes(invalid_depth_nodes, 5)

        invalid_nodes = (
            (
                LibrarySectionNode(
                    node_type=1,
                    depth=2,
                    child_count=1,
                    song_count=3,
                    first_record_index=0,
                    first_child_index=0,
                    parent_index=0xFFFF,
                    previous_index=0xFFFF,
                    next_index=1,
                ),
                nodes[0][1],
            ),
            nodes[1],
            (),
        )

        with self.assertRaises(ValueError):
            validate_library_section_nodes(invalid_nodes, 5)

    def test_validates_deepest_node_indices_against_order(self):
        nodes = (
            (
                LibrarySectionNode(
                    node_type=1,
                    depth=2,
                    child_count=2,
                    song_count=3,
                    first_record_index=0,
                    first_child_index=0,
                    parent_index=0xFFFF,
                    previous_index=0xFFFF,
                    next_index=1,
                ),
                LibrarySectionNode(
                    node_type=1,
                    depth=2,
                    child_count=1,
                    song_count=2,
                    first_record_index=3,
                    first_child_index=2,
                    parent_index=0xFFFF,
                    previous_index=0,
                    next_index=0xFFFF,
                ),
            ),
            (
                LibrarySectionNode(
                    node_type=1,
                    depth=1,
                    child_count=0,
                    song_count=1,
                    first_record_index=0,
                    first_child_index=0xFFFF,
                    parent_index=0,
                    previous_index=0xFFFF,
                    next_index=1,
                ),
                LibrarySectionNode(
                    node_type=1,
                    depth=1,
                    child_count=0,
                    song_count=2,
                    first_record_index=1,
                    first_child_index=0xFFFF,
                    parent_index=0,
                    previous_index=0,
                    next_index=2,
                ),
                LibrarySectionNode(
                    node_type=1,
                    depth=1,
                    child_count=0,
                    song_count=2,
                    first_record_index=3,
                    first_child_index=0xFFFF,
                    parent_index=1,
                    previous_index=1,
                    next_index=0xFFFF,
                ),
            ),
            (),
        )
        tables = LibrarySectionTables(
            order=(0, 1, 2, 3, 4),
            inverse=(0, 1, 2, 3, 4),
            node_indices=(0, 1, 1, 2, 2),
        )

        validate_library_section_node_indices(nodes, tables, 5)

        with self.assertRaises(ValueError):
            validate_library_section_node_indices(
                nodes,
                LibrarySectionTables(
                    order=tables.order,
                    inverse=tables.inverse,
                    node_indices=(0, 1, 2, 2, 2),
                ),
                5,
            )

    def test_interprets_node_runs_without_rewriting_the_raw_tables(self):
        nodes = (
            (
                LibrarySectionNode(
                    node_type=1,
                    depth=1,
                    child_count=0,
                    song_count=2,
                    first_record_index=2,
                    first_child_index=0xFFFF,
                    parent_index=0xFFFF,
                    previous_index=0xFFFF,
                    next_index=1,
                ),
                LibrarySectionNode(
                    node_type=1,
                    depth=1,
                    child_count=0,
                    song_count=1,
                    first_record_index=0,
                    first_child_index=0xFFFF,
                    parent_index=0xFFFF,
                    previous_index=0,
                    next_index=0xFFFF,
                ),
            ),
            (),
            (),
        )
        tables = LibrarySectionTables(
            order=(2, 1, 0),
            inverse=(2, 1, 0),
            node_indices=(1, 0, 0),
        )

        spans = interpret_library_section_index(
            LibrarySectionIndex(nodes_by_level=nodes, tables=tables),
            3,
        )

        self.assertEqual(
            [(span.level, span.node_index, span.record_indices) for span in spans],
            [(0, 0, (2, 1)), (0, 1, (0,))],
        )
        self.assertEqual(tables.order, (2, 1, 0))

    def test_zero_section_reference_returns_no_words(self):
        card = SdCardBlockDevice(sector_count=2)
        image = make_library(0)
        card.write_sector(1, image[:SECTOR_SIZE])

        self.assertEqual(read_library_section_words(card, 1, "ALLSONG", 0), ())

    def test_reads_opaque_sixteen_byte_section_nodes(self):
        image = make_library(3)
        card = SdCardBlockDevice(sector_count=6)
        card.write_sector(1, image[:SECTOR_SIZE])
        card.write_sector(2, image[SECTOR_SIZE : 2 * SECTOR_SIZE])
        card.write_sector(3, image[2 * SECTOR_SIZE : 3 * SECTOR_SIZE])
        node = bytes(range(SECTION_NODE_SIZE))
        card.write_sector(4, node.ljust(SECTOR_SIZE, b"\0"))

        self.assertEqual(
            read_library_section_nodes(card, 1, "ALLSONG", 0),
            (node,),
        )

    def test_reads_library_image_from_caller_supplied_sd_sector(self):
        image = make_library()
        card = SdCardBlockDevice(sector_count=5)
        for offset in range(len(image) // SECTOR_SIZE):
            start = offset * SECTOR_SIZE
            card.write_sector(1 + offset, image[start : start + SECTOR_SIZE])

        self.assertEqual(read_library_image(card, 1), bytes(image))
        self.assertEqual(read_library_layout(card, 1).record_count, 3)

    def test_sd_bounds_reject_an_incomplete_record_area(self):
        image = make_library()
        card = SdCardBlockDevice(sector_count=2)
        card.write_sector(1, image[:SECTOR_SIZE])

        with self.assertRaises(IndexError):
            read_library_layout(card, 1)

    def test_parses_record_area_and_section_directory(self):
        layout = parse_library_layout(bytes(make_library()))

        self.assertEqual(layout.record_count, 3)
        self.assertEqual(layout.record_area_sectors, 2)
        self.assertEqual(layout.record_area_end_sector, 3)
        self.assertEqual(layout.section("ALLSONG").node_counts, (1, 0, 0))
        self.assertEqual(layout.section("ALLSONG").sector_references, (3, 0, 0, 4, 5, 0, 0))
        self.assertEqual(layout.section("GENRE").node_counts, (0, 0, 0))

    def test_zero_records_still_has_a_valid_header(self):
        layout = parse_library_layout(bytes(make_library(0)))

        self.assertEqual(layout.record_area_sectors, 0)
        self.assertEqual(layout.record_area_end_sector, 1)

    def test_truncated_record_area_is_rejected(self):
        data = make_library(3)[:HEADER_SIZE]

        with self.assertRaises(ValueError):
            parse_library_layout(bytes(data))

    def test_unknown_section_name_is_rejected(self):
        data = make_library()
        data[0x2C : 0x2C + 5] = b"OTHER"

        with self.assertRaises(ValueError):
            parse_library_layout(bytes(data))


if __name__ == "__main__":
    unittest.main()
