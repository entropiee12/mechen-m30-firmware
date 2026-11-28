"""Bounded metadata-reader chunks recovered from the community music bank."""

from __future__ import annotations

from dataclasses import dataclass


METADATA_BUFFER_BYTES = 0x200


@dataclass(frozen=True)
class MetadataReadChunk:
    """One contiguous read that does not cross the internal buffer boundary."""

    offset: int
    length: int
    flush_after: bool


@dataclass(frozen=True)
class MetadataReadPlan:
    """Copy chunks plus the opaque reader's trailing-skip byte count."""

    chunks: tuple[MetadataReadChunk, ...]
    trailing_skip: int


def plan_metadata_read(
    start_offset: int,
    requested: int,
) -> tuple[MetadataReadChunk, ...]:
    """Split a metadata read at every 0x200-byte boundary.

    The V1.101.11/community reader loops until the requested bytes are copied.
    This models only that bounded-copy contract; source callbacks, metadata
    interpretation, and the reader's trailing skip helper remain opaque.
    """
    if start_offset < 0:
        raise ValueError("metadata start offset must be non-negative")
    if requested < 0:
        raise ValueError("metadata request length must be non-negative")

    chunks: list[MetadataReadChunk] = []
    offset = start_offset
    remaining = requested

    while remaining:
        boundary_offset = offset % METADATA_BUFFER_BYTES
        bytes_to_boundary = METADATA_BUFFER_BYTES - boundary_offset
        length = min(remaining, bytes_to_boundary)
        chunks.append(
            MetadataReadChunk(
                offset=offset,
                length=length,
                flush_after=length == bytes_to_boundary,
            )
        )
        offset += length
        remaining -= length

    return tuple(chunks)


def read_metadata_chunks(
    source: bytes,
    start_offset: int,
    requested: int,
) -> tuple[bytes, ...]:
    """Read source bytes using the recovered bounded chunk boundaries."""
    if start_offset < 0:
        raise ValueError("metadata start offset must be non-negative")
    if requested < 0:
        raise ValueError("metadata request length must be non-negative")

    end_offset = start_offset + requested
    if end_offset > len(source):
        raise ValueError("metadata request exceeds the source bytes")

    return tuple(
        source[chunk.offset : chunk.offset + chunk.length]
        for chunk in plan_metadata_read(start_offset, requested)
    )


def plan_metadata_operation(
    start_offset: int,
    copied_bytes: int,
    total_bytes: int,
) -> MetadataReadPlan:
    """Model the bounded copy and trailing skip arithmetic.

    The recovered reader copies ``copied_bytes`` in 0x200-bounded chunks and
    passes ``total_bytes - copied_bytes`` to an existing skip helper.  This
    function records that count without assigning an ABI or interpreting the
    metadata.
    """
    if copied_bytes < 0:
        raise ValueError("copied_bytes must be non-negative")
    if total_bytes < copied_bytes:
        raise ValueError("total_bytes must cover copied_bytes")

    return MetadataReadPlan(
        chunks=plan_metadata_read(start_offset, copied_bytes),
        trailing_skip=total_bytes - copied_bytes,
    )
