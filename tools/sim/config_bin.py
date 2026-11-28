"""Read indexed items from an extracted M30 ``config.bin`` image."""

from __future__ import annotations

import struct


CONFIG_INDEX_BASE = 0x10
CONFIG_INDEX_ENTRY_SIZE = 8


def read_config_bin_item(image: bytes, item_id: int) -> bytes:
    """Return one payload using the verified offset/length directory table."""
    if not isinstance(item_id, int) or isinstance(item_id, bool) or item_id < 0:
        raise ValueError("config item id must be a non-negative integer")

    index_offset = CONFIG_INDEX_BASE + item_id * CONFIG_INDEX_ENTRY_SIZE
    index_end = index_offset + CONFIG_INDEX_ENTRY_SIZE
    if index_end > len(image):
        raise ValueError(f"config item 0x{item_id:x} index is outside config.bin")

    payload_offset, payload_size = struct.unpack_from("<II", image, index_offset)
    payload_end = payload_offset + payload_size
    if payload_end > len(image):
        raise ValueError(f"config item 0x{item_id:x} payload is outside config.bin")

    return image[payload_offset:payload_end]
