"""Byte-level codec for the recovered 256-byte MUSIC.LIB record."""

from __future__ import annotations

from dataclasses import dataclass
import struct


RECORD_SIZE = 0x100
TRACK_UNSPECIFIED = 0xFFFF
TRACK_SENTINEL = 0xFEFF


def _text(data: bytes) -> str:
    if data[:2] == b"\xff\xfe":
        return data[2:].decode("utf-16-le", "replace").split("\0", 1)[0]

    return data.split(b"\0", 1)[0].decode("latin-1")


def parse_track_number(
    track_text: str,
    *,
    tag_ok: bool,
    existing_track_number: int,
) -> int:
    """Apply the recovered four-character track-number selection path."""
    if not tag_ok:
        return 0

    if existing_track_number == TRACK_SENTINEL:
        existing_track_number = 0

    if existing_track_number == 0 and track_text:
        digit_count = 0
        while digit_count < 4:
            character = track_text[digit_count : digit_count + 1]
            if not character or character == "/" or not "0" <= character <= "9":
                break
            digit_count += 1

        parsed = 0
        for character in track_text[:digit_count]:
            parsed = parsed * 10 + (ord(character) - ord("0"))
        existing_track_number = parsed

    if existing_track_number == 0:
        return TRACK_UNSPECIFIED

    return existing_track_number


@dataclass(frozen=True)
class LibraryRecord:
    title: str
    artist: str
    album: str
    genre: str
    track_text: str
    tag_ok: bool
    extension: str
    always_one: int
    location_primary: bytes
    track_number: int
    location_secondary: bytes
    previous_index: int
    next_index: int

    @property
    def cluster_no(self) -> int:
        """Return the documented little-endian cluster component of the file key."""
        return struct.unpack_from("<I", self.location_secondary, 0)[0]

    @property
    def dir_entry(self) -> int:
        """Return the documented little-endian directory-entry component."""
        return struct.unpack_from("<I", self.location_secondary, 4)[0]


def decode_record(data: bytes) -> LibraryRecord:
    """Decode one record while preserving the unresolved primary location blob."""
    if len(data) != RECORD_SIZE:
        raise ValueError("MUSIC.LIB records must be exactly 256 bytes")

    return LibraryRecord(
        title=_text(data[0x00:0x40]),
        artist=_text(data[0x40:0x58]),
        album=_text(data[0x58:0x88]),
        genre=_text(data[0x88:0xA0]),
        track_text=data[0xA0:0xA3].split(b"\0", 1)[0].decode("ascii"),
        tag_ok=data[0xA3] != 0,
        extension=data[0xA4:0xA8].split(b"\0", 1)[0].decode("latin-1"),
        always_one=data[0xA8],
        location_primary=data[0xA9:0xF2],
        track_number=struct.unpack_from("<H", data, 0xF2)[0],
        location_secondary=data[0xF4:0xFC],
        previous_index=struct.unpack_from("<H", data, 0xFC)[0],
        next_index=struct.unpack_from("<H", data, 0xFE)[0],
    )
