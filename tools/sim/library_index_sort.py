"""Candidate sort-order model of the recovered MUSIC.LIB index passes."""

from __future__ import annotations

from functools import cmp_to_key
import struct
from typing import Callable, Sequence


RECORD_SIZE = 0x100
SECTION_SPECS = {
    "ALLSONG": (0, 0x00, 0x40),
    "ALBUM": (1, 0x58, 0x30),
    "ARTIST": (2, 0x40, 0x18),
    "GENRE": (3, 0x88, 0x18),
}

MAX_REFINEMENT_SLICE = 100
REFINEMENT_BUDGET = 0x10000
STOCK_INSERTION_LIMIT = 8
INDEX_TEXT_CONVERSION_STALE_TAIL = "stale-tail"
INDEX_TEXT_CONVERSION_STOP_ZERO = "stop-zero"
INDEX_TEXT_CONVERSION_REPLACE_CONTINUE_PREFIX = "replace-continue:"
INDEX_TEXT_CONVERSION_REPLACE_CONTINUE_STALE_TAIL_PREFIX = "replace-continue-stale-tail:"
INDEX_TEXT_CONVERSION_CANDIDATE_TERMINAL_OOR_ZERO_PREFIX = "replace-continue-terminal-oor-zero:"
INDEX_TEXT_CONVERSION_CANDIDATE_TERMINAL_OOR_ZERO_STALE_TAIL_PREFIX = (
    "replace-continue-terminal-oor-zero-stale-tail:"
)


def _convert_index_text(record: bytes, offset: int, width: int) -> bytes:
    field = record[offset : offset + width]
    if field[:2] != b"\xff\xfe":
        return bytes(field)

    buffer = bytearray(
        record[offset + 2 : offset + 2 + 2 * width].ljust(2 * width, b"\0")
    )
    for index in range(width):
        character = buffer[2 * index] | (buffer[2 * index + 1] << 8)
        if character >= 256:
            break

        buffer[index] = character
        if character == 0:
            break

    return bytes(buffer[:width])


def _convert_index_text_stop_zero(record: bytes, offset: int, width: int) -> bytes:
    """Try the observed alternative: stop at a non-byte UTF-16 character.

    This mode is experimental. It is kept separate because the decompiled
    kernel call does not expose whether the destination tail is cleared before
    conversion, while the device order strongly favors a cleared tail for a
    few U+0100-and-above names.
    """
    field = record[offset : offset + width]
    if field[:2] != b"\xff\xfe":
        return bytes(field)

    source = record[offset + 2 : offset + 2 + 2 * width]
    converted = bytearray(width)

    for index in range(width):
        character_bytes = source[2 * index : 2 * index + 2].ljust(2, b"\0")
        character = character_bytes[0] | (character_bytes[1] << 8)

        if character >= 256:
            break

        converted[index] = character

        if character == 0:
            break

    return bytes(converted)


def _convert_index_text_replace_continue(
    record: bytes,
    offset: int,
    width: int,
    replacement_byte: int,
) -> bytes:
    """Experimental single-byte projection that continues after wide UTF-16."""

    field = record[offset : offset + width]
    if field[:2] != b"\xff\xfe":
        return bytes(field)

    source = record[offset + 2 : offset + 2 + 2 * width]
    converted = bytearray(width)

    for index in range(width):
        character_bytes = source[2 * index : 2 * index + 2].ljust(2, b"\0")
        character = character_bytes[0] | (character_bytes[1] << 8)

        if character >= 256:
            converted[index] = replacement_byte
            continue

        converted[index] = character
        if character == 0:
            break

    return bytes(converted)


def _convert_index_text_terminal_oor_zero(
    record: bytes,
    offset: int,
    width: int,
    replacement_byte: int,
) -> bytes:
    """Candidate: replace interior wide units, but terminate on a final one."""
    field = record[offset : offset + width]
    if field[:2] != b"\xff\xfe":
        return bytes(field)

    source = record[offset + 2 : offset + 2 + 2 * width]
    converted = bytearray(width)

    for index in range(width):
        character_bytes = source[2 * index : 2 * index + 2].ljust(2, b"\0")
        character = character_bytes[0] | (character_bytes[1] << 8)

        if character >= 256:
            next_character_bytes = source[2 * (index + 1) : 2 * (index + 2)]
            next_character_bytes = next_character_bytes.ljust(2, b"\0")
            next_character = next_character_bytes[0] | (next_character_bytes[1] << 8)

            if next_character == 0:
                converted[index] = 0
                break

            converted[index] = replacement_byte
            continue

        converted[index] = character

        if character == 0:
            break

    return bytes(converted)


def _convert_index_text_terminal_oor_zero_stale_tail(
    record: bytes,
    offset: int,
    width: int,
    replacement_byte: int,
) -> bytes:
    """Model the QEMU stub's terminal OOR rule without clearing its output tail."""
    field = record[offset : offset + width]
    if field[:2] != b"\xff\xfe":
        return bytes(field)

    converted = bytearray(
        record[offset + 2 : offset + 2 + 2 * width].ljust(2 * width, b"\0")
    )
    for index in range(width):
        character = converted[2 * index] | (converted[2 * index + 1] << 8)
        if character >= 256:
            next_character_bytes = converted[2 * (index + 1) : 2 * (index + 2)]
            next_character_bytes = next_character_bytes.ljust(2, b"\0")
            next_character = next_character_bytes[0] | (next_character_bytes[1] << 8)
            if next_character == 0:
                converted[index] = 0
                break

            converted[index] = replacement_byte
            continue

        converted[index] = character
        if character == 0:
            break

    return bytes(converted[:width])


def _convert_index_text_replace_continue_stale_tail(
    record: bytes,
    offset: int,
    width: int,
    replacement_byte: int,
) -> bytes:
    """Try in-place replacement while retaining bytes after the first NUL."""
    field = record[offset : offset + width]
    if field[:2] != b"\xff\xfe":
        return bytes(field)

    converted = bytearray(
        record[offset + 2 : offset + 2 + 2 * width].ljust(2 * width, b"\0")
    )
    for index in range(width):
        character = converted[2 * index] | (converted[2 * index + 1] << 8)
        if character >= 256:
            converted[index] = replacement_byte
            continue

        converted[index] = character
        if character == 0:
            break

    return bytes(converted[:width])


def _select_index_text_converter(
    conversion_mode: str,
) -> Callable[[bytes, int, int], bytes]:
    if conversion_mode == INDEX_TEXT_CONVERSION_STALE_TAIL:
        return _convert_index_text

    if conversion_mode == INDEX_TEXT_CONVERSION_STOP_ZERO:
        return _convert_index_text_stop_zero

    if conversion_mode.startswith(INDEX_TEXT_CONVERSION_REPLACE_CONTINUE_STALE_TAIL_PREFIX):
        replacement_text = conversion_mode[
            len(INDEX_TEXT_CONVERSION_REPLACE_CONTINUE_STALE_TAIL_PREFIX) :
        ]
        try:
            replacement_byte = int(replacement_text, 16)
        except ValueError as error:
            raise ValueError(
                "replace-continue-stale-tail mode needs a hexadecimal byte"
            ) from error

        if not 0 <= replacement_byte <= 0xFF:
            raise ValueError(
                "replace-continue-stale-tail replacement must fit one byte"
            )

        return lambda record, offset, width: _convert_index_text_replace_continue_stale_tail(
            record,
            offset,
            width,
            replacement_byte,
        )

    if conversion_mode.startswith(
        INDEX_TEXT_CONVERSION_CANDIDATE_TERMINAL_OOR_ZERO_STALE_TAIL_PREFIX
    ):
        replacement_text = conversion_mode[
            len(INDEX_TEXT_CONVERSION_CANDIDATE_TERMINAL_OOR_ZERO_STALE_TAIL_PREFIX) :
        ]
        try:
            replacement_byte = int(replacement_text, 16)
        except ValueError as error:
            raise ValueError(
                "terminal-oor-zero-stale-tail mode needs a hexadecimal byte"
            ) from error

        if not 0 <= replacement_byte <= 0xFF:
            raise ValueError(
                "terminal-oor-zero-stale-tail replacement must fit one byte"
            )

        return lambda record, offset, width: _convert_index_text_terminal_oor_zero_stale_tail(
            record,
            offset,
            width,
            replacement_byte,
        )

    if conversion_mode.startswith(INDEX_TEXT_CONVERSION_CANDIDATE_TERMINAL_OOR_ZERO_PREFIX):
        replacement_text = conversion_mode[
            len(INDEX_TEXT_CONVERSION_CANDIDATE_TERMINAL_OOR_ZERO_PREFIX) :
        ]
        try:
            replacement_byte = int(replacement_text, 16)
        except ValueError as error:
            raise ValueError(
                "terminal-oor-zero mode needs a hexadecimal replacement byte"
            ) from error

        if not 0 <= replacement_byte <= 0xFF:
            raise ValueError(
                "terminal-oor-zero replacement must fit one byte"
            )

        return lambda record, offset, width: _convert_index_text_terminal_oor_zero(
            record,
            offset,
            width,
            replacement_byte,
        )

    if conversion_mode.startswith(INDEX_TEXT_CONVERSION_REPLACE_CONTINUE_PREFIX):
        replacement_text = conversion_mode[len(INDEX_TEXT_CONVERSION_REPLACE_CONTINUE_PREFIX) :]
        try:
            replacement_byte = int(replacement_text, 16)
        except ValueError as error:
            raise ValueError("replace-continue mode needs a hexadecimal byte") from error

        if not 0 <= replacement_byte <= 0xFF:
            raise ValueError("replace-continue replacement must fit one byte")

        return lambda record, offset, width: _convert_index_text_replace_continue(
            record,
            offset,
            width,
            replacement_byte,
        )

    raise ValueError(
        "unsupported MUSIC.LIB index text conversion mode: "
        f"{conversion_mode}"
    )


def _compare_text(left: bytes, right: bytes) -> int:
    for left_byte, right_byte in zip(left, right):
        if 0x61 <= left_byte <= 0x7A:
            left_byte -= 32

        if 0x61 <= right_byte <= 0x7A:
            right_byte -= 32

        if left_byte != right_byte:
            return -1 if left_byte < right_byte else 1

        if left_byte == 0:
            return 0

    return 0


def _signed_track_byte(record: bytes) -> int:
    return struct.unpack_from("<b", record, 0xF2)[0]


def _stock_sort_indices(
    indices: list[int],
    compare: Callable[[int, int], int],
) -> tuple[int, ...]:
    """Run the recovered in-place quicksort on an index array.

    The firmware uses a middle-element pivot, Hoare-style partitioning, an
    explicit range stack, and a selection-style insertion sort for ranges of
    eight elements or fewer.  Keeping this separate from ``sorted`` matters:
    equal keys are intentionally not stable in the stock implementation.
    """
    if len(indices) < 2:
        return tuple(indices)

    pending_ranges: list[tuple[int, int]] = []
    range_start = 0
    range_end = len(indices) - 1

    def swap(left: int, right: int) -> None:
        indices[left], indices[right] = indices[right], indices[left]

    while True:
        item_count = range_end - range_start + 1

        if item_count <= STOCK_INSERTION_LIMIT:
            insertion_end = range_end

            while range_start < insertion_end:
                selected_index = range_start
                selected_count = 0

                for candidate_index in range(range_start + 1, insertion_end + 1):
                    if compare(indices[candidate_index], indices[selected_index]) > 0:
                        selected_index = candidate_index
                        selected_count += 1

                if selected_count == insertion_end - range_start:
                    break

                if selected_index != insertion_end:
                    swap(selected_index, insertion_end)

                insertion_end -= 1

            if not pending_ranges:
                return tuple(indices)

            range_start, range_end = pending_ranges.pop()
            continue

        pivot_index = range_start + (item_count >> 1)
        swap(pivot_index, range_start)
        left_index = range_start
        right_index = range_end + 1

        while True:
            left_index += 1

            while (
                left_index <= range_end
                and compare(indices[left_index], indices[range_start]) <= 0
            ):
                left_index += 1

            right_index -= 1

            while (
                right_index > range_start
                and compare(indices[right_index], indices[range_start]) >= 0
            ):
                right_index -= 1

            if left_index > right_index:
                break

            swap(left_index, right_index)

        if right_index != range_start:
            swap(range_start, right_index)

        left_count = right_index - range_start
        right_count = range_end - left_index + 1

        if left_count <= right_count:
            if left_index < range_end:
                pending_ranges.append((left_index, range_end))

            if range_start + 1 < right_index:
                range_end = right_index - 1
                continue
        else:
            if range_start + 1 < right_index:
                pending_ranges.append((range_start, right_index - 1))

            if left_index < range_end:
                range_start = left_index
                continue

        if not pending_ranges:
            return tuple(indices)

        range_start, range_end = pending_ranges.pop()


def sort_record_indices(
    records: Sequence[bytes],
    section_name: str,
    previous_rank: Sequence[int] | None = None,
) -> tuple[int, ...]:
    """Return the recovered stable sort order without writing an index file."""
    if section_name not in SECTION_SPECS:
        raise ValueError(f"unsupported MUSIC.LIB section: {section_name}")

    section_number, field_offset, field_width = SECTION_SPECS[section_name]
    for record in records:
        if len(record) != RECORD_SIZE:
            raise ValueError("MUSIC.LIB sort records must be exactly 256 bytes")

    if section_number and (previous_rank is None or len(previous_rank) != len(records)):
        raise ValueError("non-first section sorting requires one previous rank per record")

    keys = tuple(
        _convert_index_text(record, field_offset, field_width)
        for record in records
    )

    def compare(left_index: int, right_index: int) -> int:
        result = _compare_text(keys[left_index], keys[right_index])
        if result:
            return result

        if section_name == "ALBUM":
            left_track = _signed_track_byte(records[left_index])
            right_track = _signed_track_byte(records[right_index])
            if left_track != right_track:
                return -1 if left_track < right_track else 1

        if section_number:
            left_rank = previous_rank[left_index]
            right_rank = previous_rank[right_index]
            if left_rank != right_rank:
                return -1 if left_rank < right_rank else 1

        return 0

    return tuple(sorted(range(len(records)), key=cmp_to_key(compare)))


def refine_sort_record_indices(
    records: Sequence[bytes],
    section_name: str,
    previous_rank: Sequence[int] | None = None,
    *,
    conversion_mode: str = INDEX_TEXT_CONVERSION_STALE_TAIL,
) -> tuple[int, ...]:
    """Apply the recovered stock sort and bounded tie refinement.

    This is deliberately separate from ``sort_record_indices``. The latter
    remains the small stable candidate comparator used by the offline builder;
    this opt-in model follows the recovered stock quicksort and
    ``FUN_40114d54`` path, which re-reads equal runs in bounded key slices.
    """
    if section_name not in SECTION_SPECS:
        raise ValueError(f"unsupported MUSIC.LIB section: {section_name}")

    section_number, field_offset, field_width = SECTION_SPECS[section_name]
    for record in records:
        if len(record) != RECORD_SIZE:
            raise ValueError("MUSIC.LIB sort records must be exactly 256 bytes")

    if section_number and (previous_rank is None or len(previous_rank) != len(records)):
        raise ValueError("non-first section sorting requires one previous rank per record")

    convert_index_text = _select_index_text_converter(conversion_mode)
    keys = tuple(
        convert_index_text(record, field_offset, field_width)
        for record in records
    )

    initial_key_length = min(
        field_width,
        max(1, REFINEMENT_BUDGET // len(records)),
    )

    def compare_segment(
        left_index: int,
        right_index: int,
        offset: int,
        length: int,
    ) -> int:
        return _compare_text(
            keys[left_index][offset : offset + length],
            keys[right_index][offset : offset + length],
        )

    order = list(
        _stock_sort_indices(
            list(range(len(records))),
            lambda left, right: compare_segment(
                left,
                right,
                0,
                initial_key_length,
            ),
        )
    )

    def refine_run(run: list[int], offset: int) -> list[int]:
        if len(run) < 2 or offset >= field_width:
            return run

        refinement_length = min(
            MAX_REFINEMENT_SLICE,
            field_width - offset,
            REFINEMENT_BUDGET // len(run),
        )

        if refinement_length <= 0:
            return run

        refined_run = list(
            _stock_sort_indices(
                run,
                lambda left, right: compare_segment(
                    left,
                    right,
                    offset,
                    refinement_length,
                ),
            )
        )
        next_offset = offset + refinement_length
        result: list[int] = []
        start = 0

        for position in range(1, len(refined_run) + 1):
            is_end = position == len(refined_run)
            has_different_segment = (
                not is_end
                and compare_segment(
                    refined_run[position - 1],
                    refined_run[position],
                    offset,
                    refinement_length,
                )
                != 0
            )

            if is_end or has_different_segment:
                result.extend(refine_run(refined_run[start:position], next_offset))
                start = position

        return result

    refined_order: list[int] = []
    start = 0

    for position in range(1, len(order) + 1):
        is_end = position == len(order)
        has_different_prefix = (
            not is_end
            and compare_segment(
                order[position - 1],
                order[position],
                0,
                initial_key_length,
            )
            != 0
        )

        if is_end or has_different_prefix:
            refined_order.extend(refine_run(order[start:position], initial_key_length))
            start = position

    if not section_number:
        return tuple(refined_order)

    grouped_order: list[int] = []
    start = 0

    while start < len(refined_order):
        end = start + 1

        while (
            end < len(refined_order)
            and compare_segment(
                refined_order[end - 1],
                refined_order[end],
                0,
                field_width,
            )
            == 0
        ):
            end += 1

        def compare_group(left_index: int, right_index: int) -> int:
            if section_name == "ALBUM":
                left_track = _signed_track_byte(records[left_index])
                right_track = _signed_track_byte(records[right_index])

                if left_track != right_track:
                    return -1 if left_track < right_track else 1

            left_rank = previous_rank[left_index]
            right_rank = previous_rank[right_index]

            if left_rank != right_rank:
                return -1 if left_rank < right_rank else 1

            return 0

        grouped_order.extend(
            _stock_sort_indices(
                refined_order[start:end],
                compare_group,
            )
        )
        start = end

    return tuple(grouped_order)


def group_indices_from_runs(
    order: Sequence[int],
    run_lengths: Sequence[int],
) -> tuple[int, ...]:
    """Map each record index to its zero-based group from sorted runs."""
    if sorted(order) != list(range(len(order))):
        raise ValueError("library sort order is not a record-index permutation")

    if sum(run_lengths) != len(order):
        raise ValueError("library run lengths do not cover the sort order")

    groups = [0] * len(order)
    position = 0
    for group, run_length in enumerate(run_lengths):
        if run_length <= 0:
            raise ValueError("library run lengths must be positive")

        for record_index in order[position : position + run_length]:
            if not 0 <= record_index < len(order):
                raise ValueError("library group contains an invalid record index")

            groups[record_index] = group

        position += run_length

    return tuple(groups)


def deepest_group_indices_from_runs(
    order: Sequence[int],
    run_lengths: Sequence[Sequence[int]],
) -> tuple[int, ...]:
    """Map each record to the deepest node group emitted for a section."""
    if not run_lengths:
        return ()

    return group_indices_from_runs(order, run_lengths[-1])


def derive_section_run_lengths(
    records: Sequence[bytes],
    section_name: str,
    order: Sequence[int],
    *,
    album_group_indices: Sequence[int] | None = None,
    artist_group_indices: Sequence[int] | None = None,
    conversion_mode: str = INDEX_TEXT_CONVERSION_STALE_TAIL,
) -> tuple[tuple[int, ...], ...]:
    """Derive the recovered nested group runs without writing index bytes."""
    if section_name not in SECTION_SPECS:
        raise ValueError(f"unsupported MUSIC.LIB section: {section_name}")

    section_number, field_offset, field_width = SECTION_SPECS[section_name]
    if len(order) != len(records):
        raise ValueError("library sort order must contain every record")

    if sorted(order) != list(range(len(records))):
        raise ValueError("library sort order is not a record-index permutation")

    if section_number >= 2:
        if album_group_indices is None or len(album_group_indices) != len(records):
            raise ValueError("artist and genre grouping require album groups")

    if section_number == 3:
        if artist_group_indices is None or len(artist_group_indices) != len(records):
            raise ValueError("genre grouping requires artist groups")

    if section_number == 0:
        return ()

    convert_index_text = _select_index_text_converter(conversion_mode)
    keys = tuple(
        convert_index_text(record, field_offset, field_width)
        for record in records
    )
    level_count = section_number
    run_lengths: list[list[int]] = [[] for _ in range(level_count)]
    current_lengths = [0] * level_count

    for position, record_index in enumerate(order):
        if position:
            previous_index = order[position - 1]
            break_level = level_count
            if _compare_text(keys[previous_index], keys[record_index]) != 0:
                break_level = 0
            elif level_count > 1:
                if section_name == "ARTIST":
                    if album_group_indices[previous_index] != album_group_indices[record_index]:
                        break_level = 1
                elif artist_group_indices[previous_index] != artist_group_indices[record_index]:
                    break_level = 1
                elif (
                    section_name == "GENRE"
                    and album_group_indices[previous_index] != album_group_indices[record_index]
                ):
                    break_level = 2

            for level in range(break_level, level_count):
                run_lengths[level].append(current_lengths[level])
                current_lengths[level] = 0

        for level in range(level_count):
            current_lengths[level] += 1

    for level in range(level_count):
        run_lengths[level].append(current_lengths[level])

    return tuple(tuple(lengths) for lengths in run_lengths)
