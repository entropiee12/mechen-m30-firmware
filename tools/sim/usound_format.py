"""Evidence-backed byte-format boundary used before the usound software ring."""

from __future__ import annotations


def copy_bytes(data: bytes, byte_count: int | None = None) -> bytes:
    """Model ``FUN_bfc30cac``'s byte-for-byte copy."""

    if byte_count is None:
        byte_count = len(data)
    if byte_count < 0 or byte_count > len(data):
        raise ValueError("byte_count must fit inside data")
    return data[:byte_count]


def expand_three_to_four(data: bytes, byte_count: int | None = None) -> bytes:
    """Model ``FUN_bfc30ce8`` exactly, including its trailing-byte drop."""

    if byte_count is None:
        byte_count = len(data)
    if byte_count < 0 or byte_count > len(data):
        raise ValueError("byte_count must fit inside data")
    output = bytearray()
    for offset in range(0, byte_count - 2, 3):
        output.extend((0, data[offset], data[offset + 1], data[offset + 2]))
    return bytes(output)


def ring_write_size(byte_count: int, expanded: bool) -> int:
    """Return the caller's observed output-size arithmetic."""

    if byte_count < 0:
        raise ValueError("byte_count must be non-negative")
    return byte_count * 4 // 3 if expanded else byte_count
