"""Byte-layout model for the recovered ``MUSIC.LIB`` file header."""

from __future__ import annotations

from dataclasses import dataclass
import struct

from library_record_codec import LibraryRecord, decode_record
from library_scan import MAX_LIBRARY_RECORDS
from sd_block_device import SdCardBlockDevice


SECTOR_SIZE = 512
HEADER_SIZE = 0x200
RECORD_SIZE = 0x100
SECTION_NODE_SIZE = 0x10
RECORD_START_SECTOR = 1
ORDER_TABLE_REFERENCE = 3
INVERSE_TABLE_REFERENCE = 4
NODE_INDEX_TABLE_REFERENCE = 5
NULL_INDEX = 0xFFFF
SECTION_HEADERS = (
    ("ALLSONG", 0x10),
    ("ALBUM", 0x2C),
    ("ARTIST", 0x48),
    ("GENRE", 0x64),
)


@dataclass(frozen=True)
class LibrarySection:
    """One section's counts and opaque sector references."""

    name: str
    node_counts: tuple[int, int, int]
    sector_references: tuple[int, ...]


@dataclass(frozen=True)
class MusicLibraryLayout:
    """Verified file geometry without interpreting index node contents."""

    record_count: int
    record_area_sectors: int
    sections: tuple[LibrarySection, ...]

    @property
    def record_area_end_sector(self) -> int:
        """Return the first sector after the padded record area."""
        return RECORD_START_SECTOR + self.record_area_sectors

    def section(self, name: str) -> LibrarySection:
        for section in self.sections:
            if section.name == name:
                return section

        raise KeyError(name)


@dataclass(frozen=True)
class LibrarySectionNode:
    """Decoded fields from one observed 16-byte index node."""

    node_type: int
    depth: int
    child_count: int
    song_count: int
    first_record_index: int
    first_child_index: int
    parent_index: int
    previous_index: int
    next_index: int


@dataclass(frozen=True)
class LibrarySectionTables:
    """Per-record tables emitted alongside one section's index nodes."""

    order: tuple[int, ...]
    inverse: tuple[int, ...]
    node_indices: tuple[int, ...]


@dataclass(frozen=True)
class LibrarySectionIndex:
    """Typed nodes and per-record tables for one section."""

    nodes_by_level: tuple[tuple[LibrarySectionNode, ...], ...]
    tables: LibrarySectionTables


@dataclass(frozen=True)
class LibrarySectionNodeSpan:
    """Record indices covered by one node in section sort order."""

    level: int
    node_index: int
    record_indices: tuple[int, ...]


def decode_library_section_node(data: bytes) -> LibrarySectionNode:
    """Decode one node using the recovered little-endian field layout."""
    if len(data) != SECTION_NODE_SIZE:
        raise ValueError("library section node must contain exactly 16 bytes")

    fields = struct.unpack("<BBHHHHHHH", data)

    return LibrarySectionNode(
        node_type=fields[0],
        depth=fields[1],
        child_count=fields[2],
        song_count=fields[3],
        first_record_index=fields[4],
        first_child_index=fields[5],
        parent_index=fields[6],
        previous_index=fields[7],
        next_index=fields[8],
    )


def parse_library_layout(data: bytes) -> MusicLibraryLayout:
    """Parse the documented header and section directory from one library."""
    if len(data) < HEADER_SIZE:
        raise ValueError("MUSIC.LIB data is shorter than its header")

    record_count = struct.unpack_from("<H", data, 0x08)[0]
    record_bytes = record_count * RECORD_SIZE
    record_area_sectors = (record_bytes + SECTOR_SIZE - 1) // SECTOR_SIZE
    required_bytes = (RECORD_START_SECTOR + record_area_sectors) * SECTOR_SIZE
    if len(data) < required_bytes:
        raise ValueError("MUSIC.LIB data is shorter than its record area")

    sections: list[LibrarySection] = []
    for name, offset in SECTION_HEADERS:
        raw_name = data[offset : offset + 8].split(b"\0", 1)[0]
        if raw_name.decode("ascii") != name:
            raise ValueError(f"unexpected MUSIC.LIB section name at 0x{offset:x}")

        node_counts = struct.unpack_from("<3H", data, offset + 8)
        sector_references = struct.unpack_from("<7H", data, offset + 14)
        sections.append(
            LibrarySection(
                name=name,
                node_counts=node_counts,
                sector_references=sector_references,
            )
        )

    return MusicLibraryLayout(
        record_count=record_count,
        record_area_sectors=record_area_sectors,
        sections=tuple(sections),
    )


def read_library_image(
    device: SdCardBlockDevice,
    start_sector: int,
) -> bytes:
    """Read one ``MUSIC.LIB`` image from a caller-supplied start sector."""
    if start_sector < 0:
        raise ValueError("library start sector must be non-negative")

    header = device.read_sector(start_sector)
    record_count = struct.unpack_from("<H", header, 0x08)[0]
    record_bytes = record_count * RECORD_SIZE
    record_area_sectors = (record_bytes + SECTOR_SIZE - 1) // SECTOR_SIZE

    return header + b"".join(
        device.read_sector(start_sector + 1 + offset)
        for offset in range(record_area_sectors)
    )


def read_library_layout(
    device: SdCardBlockDevice,
    start_sector: int,
) -> MusicLibraryLayout:
    """Parse a library image read from the SD block boundary."""
    return parse_library_layout(read_library_image(device, start_sector))


def read_library_record(
    device: SdCardBlockDevice,
    start_sector: int,
    record_index: int,
) -> LibraryRecord:
    """Read and decode one bounded record from a caller-supplied SD image."""
    if start_sector < 0:
        raise ValueError("library start sector must be non-negative")

    if record_index < 0:
        raise ValueError("library record index must be non-negative")

    header = device.read_sector(start_sector)
    record_count = struct.unpack_from("<H", header, 0x08)[0]
    if record_index >= record_count:
        raise IndexError("library record index is outside the MUSIC.LIB record area")

    record_offset = HEADER_SIZE + record_index * RECORD_SIZE
    sector_offset, offset_in_sector = divmod(record_offset, SECTOR_SIZE)
    first_sector = device.read_sector(start_sector + sector_offset)
    record_end = offset_in_sector + RECORD_SIZE
    if record_end <= SECTOR_SIZE:
        record_bytes = first_sector[offset_in_sector:record_end]

    else:
        second_sector = device.read_sector(start_sector + sector_offset + 1)
        record_bytes = (
            first_sector[offset_in_sector:]
            + second_sector[: record_end - SECTOR_SIZE]
        )

    return decode_record(record_bytes)


def find_library_record_index(
    device: SdCardBlockDevice,
    start_sector: int,
    location_key: bytes,
) -> int | None:
    """Find an active record by the firmware's bounded eight-byte file key."""
    if start_sector < 0:
        raise ValueError("library start sector must be non-negative")

    if len(location_key) != 8:
        raise ValueError("library location key must contain exactly eight bytes")

    header = device.read_sector(start_sector)
    record_count = struct.unpack_from("<H", header, 0x08)[0]
    search_count = min(record_count, MAX_LIBRARY_RECORDS)

    for record_index in range(search_count):
        record = read_library_record(device, start_sector, record_index)
        if record.always_one == 1 and record.location_secondary == location_key:
            return record_index

    return None


def read_library_section_words(
    device: SdCardBlockDevice,
    start_sector: int,
    section_name: str,
    reference_index: int,
) -> tuple[int, ...]:
    """Read a referenced section table as raw little-endian 16-bit words."""
    if start_sector < 0:
        raise ValueError("library start sector must be non-negative")

    if not 0 <= reference_index < 7:
        raise ValueError("section reference index must fit the seven header entries")

    layout = parse_library_layout(read_library_image(device, start_sector))
    section = layout.section(section_name)
    sector = section.sector_references[reference_index]
    if sector == 0:
        return ()

    word_count = layout.record_count
    byte_count = word_count * 2
    sector_count = (byte_count + SECTOR_SIZE - 1) // SECTOR_SIZE
    data = b"".join(
        device.read_sector(start_sector + sector + offset)
        for offset in range(sector_count)
    )

    return struct.unpack_from(f"<{word_count}H", data, 0)


def read_library_section_tables(
    device: SdCardBlockDevice,
    start_sector: int,
    section_name: str,
) -> LibrarySectionTables:
    """Read the observed order, inverse, and node-index tables for a section."""
    return LibrarySectionTables(
        order=read_library_section_words(
            device,
            start_sector,
            section_name,
            ORDER_TABLE_REFERENCE,
        ),
        inverse=read_library_section_words(
            device,
            start_sector,
            section_name,
            INVERSE_TABLE_REFERENCE,
        ),
        node_indices=read_library_section_words(
            device,
            start_sector,
            section_name,
            NODE_INDEX_TABLE_REFERENCE,
        ),
    )


def validate_library_section_tables(
    tables: LibrarySectionTables,
    record_count: int,
) -> None:
    """Reject table data that cannot represent the recovered index contract."""
    if record_count < 0:
        raise ValueError("library record count must be non-negative")

    expected_indices = list(range(record_count))
    if sorted(tables.order) != expected_indices:
        raise ValueError("library order table is not a record-index permutation")

    if sorted(tables.inverse) != expected_indices:
        raise ValueError("library inverse table is not a record-index permutation")

    if len(tables.node_indices) not in (0, record_count):
        raise ValueError("library node-index table has an unexpected length")

    for position, record_index in enumerate(tables.order):
        if tables.inverse[record_index] != position:
            raise ValueError("library order and inverse tables disagree")


def validate_library_section_nodes(
    nodes_by_level: tuple[tuple[LibrarySectionNode, ...], ...],
    record_count: int,
) -> None:
    """Reject node links that violate the recovered writer contract."""
    if record_count < 0:
        raise ValueError("library record count must be non-negative")

    if len(nodes_by_level) != 3:
        raise ValueError("library index must expose exactly three node levels")

    populated_level_count = max(
        (level + 1 for level, nodes in enumerate(nodes_by_level) if nodes),
        default=0,
    )

    for level, nodes in enumerate(nodes_by_level):
        song_total = 0
        has_child_level = (
            level + 1 < len(nodes_by_level)
            and bool(nodes_by_level[level + 1])
        )
        expected_child_index = 0

        for index, node in enumerate(nodes):
            if node.node_type != 1:
                raise ValueError("library node type is not the recovered value 1")

            expected_depth = populated_level_count - level
            if node.depth != expected_depth:
                raise ValueError(
                    "library node depth does not match its populated level"
                )

            if node.song_count == 0:
                raise ValueError("library node must contain at least one record")

            if node.first_record_index >= record_count:
                raise ValueError("library node points outside the record area")

            expected_previous = NULL_INDEX if index == 0 else index - 1
            expected_next = NULL_INDEX if index == len(nodes) - 1 else index + 1
            if node.previous_index != expected_previous:
                raise ValueError("library node previous link is inconsistent")

            if node.next_index != expected_next:
                raise ValueError("library node next link is inconsistent")

            if level == 0:
                if node.parent_index != NULL_INDEX:
                    raise ValueError("top-level library node has a parent link")

            elif (
                node.parent_index == NULL_INDEX
                or node.parent_index >= len(nodes_by_level[level - 1])
            ):
                raise ValueError("library node parent link is outside the previous level")

            if not has_child_level:
                if node.child_count != 0 or node.first_child_index != NULL_INDEX:
                    raise ValueError("leaf library node has a child link")

            else:
                if node.child_count == 0 or node.first_child_index == NULL_INDEX:
                    raise ValueError("library node with a child level has no child link")

                child_end = node.first_child_index + node.child_count
                if child_end > len(nodes_by_level[level + 1]):
                    raise ValueError("library node child range is outside the next level")

                if node.first_child_index != expected_child_index:
                    raise ValueError("library node child ranges are not contiguous")

                children = nodes_by_level[level + 1][node.first_child_index:child_end]
                if sum(child.song_count for child in children) != node.song_count:
                    raise ValueError("library child song counts do not match the parent")

                for child in children:
                    if child.parent_index != index:
                        raise ValueError("library child points to the wrong parent")

                    if child.depth + 1 != node.depth:
                        raise ValueError("library parent and child depths disagree")

                expected_child_index = child_end

            song_total += node.song_count

        if nodes and song_total != record_count:
            raise ValueError("library node song counts do not cover the record area")

        if has_child_level and expected_child_index != len(nodes_by_level[level + 1]):
            raise ValueError("library child ranges do not cover the next level")


def validate_library_section_node_indices(
    nodes_by_level: tuple[tuple[LibrarySectionNode, ...], ...],
    tables: LibrarySectionTables,
    record_count: int,
) -> None:
    """Validate reference 5 against the deepest node level's record runs."""
    validate_library_section_tables(tables, record_count)

    if len(nodes_by_level) != 3:
        raise ValueError("library index must expose exactly three node levels")

    deepest_nodes: tuple[LibrarySectionNode, ...] = ()
    for nodes in reversed(nodes_by_level):
        if nodes:
            deepest_nodes = nodes
            break

    if not deepest_nodes:
        if tables.node_indices:
            raise ValueError("library node-index table has no corresponding nodes")

        return

    if len(tables.node_indices) != record_count:
        raise ValueError("deepest library node-index table must cover every record")

    expected_indices: list[int | None] = [None] * record_count
    position = 0
    for node_index, node in enumerate(deepest_nodes):
        if node.song_count <= 0:
            raise ValueError("deepest library node must contain at least one record")

        end = position + node.song_count
        if end > len(tables.order):
            raise ValueError("deepest library nodes exceed the sort order")

        first_record_index = tables.order[position]
        if node.first_record_index != first_record_index:
            raise ValueError("deepest node first record disagrees with the sort order")

        for record_index in tables.order[position:end]:
            expected_indices[record_index] = node_index

        position = end

    if position != record_count:
        raise ValueError("deepest library nodes do not cover the record area")

    if any(index is None for index in expected_indices):
        raise ValueError("deepest library nodes leave records unassigned")

    if tuple(expected_indices) != tables.node_indices:
        raise ValueError("library node-index table disagrees with deepest nodes")


def interpret_library_section_index(
    index: LibrarySectionIndex,
    record_count: int,
) -> tuple[LibrarySectionNodeSpan, ...]:
    """Map validated node runs to record indices without assigning UI meaning."""
    validate_library_section_nodes(index.nodes_by_level, record_count)
    validate_library_section_node_indices(
        index.nodes_by_level,
        index.tables,
        record_count,
    )

    spans: list[LibrarySectionNodeSpan] = []
    for level, nodes in enumerate(index.nodes_by_level):
        position = 0
        for node_index, node in enumerate(nodes):
            end = position + node.song_count
            record_indices = index.tables.order[position:end]
            if node.first_record_index != record_indices[0]:
                raise ValueError("library node start disagrees with its order span")

            spans.append(
                LibrarySectionNodeSpan(
                    level=level,
                    node_index=node_index,
                    record_indices=record_indices,
                )
            )
            position = end

    return tuple(spans)


def read_library_section_index(
    device: SdCardBlockDevice,
    start_sector: int,
    section_name: str,
) -> LibrarySectionIndex:
    """Read one section without assigning user-facing sort or tree semantics."""
    nodes_by_level = tuple(
        tuple(
            decode_library_section_node(raw_node)
            for raw_node in read_library_section_nodes(
                device,
                start_sector,
                section_name,
                level,
            )
        )
        for level in range(3)
    )

    return LibrarySectionIndex(
        nodes_by_level=nodes_by_level,
        tables=read_library_section_tables(device, start_sector, section_name),
    )


def read_library_section_nodes(
    device: SdCardBlockDevice,
    start_sector: int,
    section_name: str,
    level: int,
) -> tuple[bytes, ...]:
    """Read referenced section nodes as bounded opaque 16-byte records."""
    if start_sector < 0:
        raise ValueError("library start sector must be non-negative")

    if not 0 <= level < 3:
        raise ValueError("section node level must be zero, one, or two")

    layout = parse_library_layout(read_library_image(device, start_sector))
    section = layout.section(section_name)
    node_count = section.node_counts[level]
    sector = section.sector_references[level]
    if node_count == 0 or sector == 0:
        return ()

    byte_count = node_count * SECTION_NODE_SIZE
    sector_count = (byte_count + SECTOR_SIZE - 1) // SECTOR_SIZE
    data = b"".join(
        device.read_sector(start_sector + sector + offset)
        for offset in range(sector_count)
    )

    return tuple(
        data[offset : offset + SECTION_NODE_SIZE]
        for offset in range(0, byte_count, SECTION_NODE_SIZE)
    )
