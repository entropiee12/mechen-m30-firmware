"""Safe fallback record contract from the M30 library scan."""

from __future__ import annotations

from dataclasses import dataclass


def format_short_name(raw_name: bytes) -> str:
    """Format the recovered eleven-byte 8.3 name buffer as ``NAME.EXT``."""
    if len(raw_name) < 11:
        raise ValueError("8.3 name buffer must contain at least 11 bytes")

    if raw_name[:2] == b"\xff\xfe":
        return raw_name.decode("utf-16-le", "replace").split("\0", 1)[0]

    name = bytearray(raw_name[:11]) + bytearray(1)
    for index in range(11, 8, -1):
        name[index] = name[index - 1]

    end = 7
    while end != 0 and name[end] == ord(" "):
        end -= 1
    end += 1
    name[end] = ord(".")

    for index in range(9, 12):
        if name[index] != ord(" "):
            end += 1
            name[end] = name[index]

    if name[end] != ord("."):
        end += 1

    return bytes(name[:end]).decode("ascii")


@dataclass(frozen=True)
class LibraryFallbackRecord:
    """Metadata emitted when a tag reader is unavailable or rejects a file."""

    title: str
    artist: str = "No Artist"
    album: str = "No Album"
    genre: str = "No Genre"
    tag_ok: bool = False

    @classmethod
    def from_short_name(cls, short_name: str) -> "LibraryFallbackRecord":
        if not short_name:
            raise ValueError("fallback record requires a non-empty short name")

        return cls(title=short_name)

    @classmethod
    def from_raw_short_name(cls, raw_name: bytes) -> "LibraryFallbackRecord":
        return cls.from_short_name(format_short_name(raw_name))
