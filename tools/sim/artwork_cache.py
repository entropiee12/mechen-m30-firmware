"""Host model of the observed ``ALBUM.PIC`` cache record boundary."""

from __future__ import annotations

from dataclasses import dataclass


ALBUM_PIC_HEADER_BYTES = 6
ALBUM_PIC_OK = "OK"
ALBUM_PIC_NONE = "NO"
RGB565_BYTES_PER_PIXEL = 2
ALBUM_PIC_CONTAINER_HEADER_BYTES = 0x200
ALBUM_PIC_CONTAINER_SECTOR_BYTES = 512
ALBUM_PIC_CONTAINER_SECTOR_COUNT = 250
ALBUM_PIC_CONTAINER_BYTES = (
    ALBUM_PIC_CONTAINER_SECTOR_BYTES * ALBUM_PIC_CONTAINER_SECTOR_COUNT
)
ALBUM_PIC_CONTAINER_MAGIC = b"ALBUM"
ALBUM_PIC_CONTAINER_MARKER_OFFSET = 0x10
ALBUM_PIC_CONTAINER_MARKER = b"NOA"
ALBUM_PIC_CONTAINER_PAYLOAD_BYTES = 150 * 150 * RGB565_BYTES_PER_PIXEL


@dataclass(frozen=True)
class AlbumPicRecord:
    """One cache record without interpreting the RGB565 byte order."""

    status: str
    x: int
    y: int
    width: int
    height: int
    pixels: bytes

    def __post_init__(self) -> None:
        if self.status not in (ALBUM_PIC_OK, ALBUM_PIC_NONE):
            raise ValueError("ALBUM.PIC status must be OK or NO")
        geometry = (self.x, self.y, self.width, self.height)

        if any(not 0 <= value <= 0xFF for value in geometry):
            raise ValueError("ALBUM.PIC geometry fields must fit one byte")

        if self.status == ALBUM_PIC_NONE:
            if (self.x, self.y, self.width, self.height) != (0, 0, 0, 0):
                raise ValueError("NO ALBUM.PIC records must have zero geometry")
            if self.pixels:
                raise ValueError("NO ALBUM.PIC records must not contain pixels")

        if self.status == ALBUM_PIC_OK:
            if self.width == 0 or self.height == 0:
                raise ValueError("OK ALBUM.PIC records must have non-zero dimensions")
            expected_size = self.width * self.height * RGB565_BYTES_PER_PIXEL
            if len(self.pixels) != expected_size:
                raise ValueError("OK ALBUM.PIC pixel payload has the wrong size")

    def encode(self) -> bytes:
        """Serialize the observed six-byte header and optional pixel payload."""
        header = self.status.encode("ascii") + bytes(
            (self.x, self.y, self.width, self.height)
        )

        return header + self.pixels


@dataclass(frozen=True)
class AlbumPicContainer:
    """Observed fixed-size SD container holding one 150x150 RGB565 payload."""

    header: bytes
    pixels: bytes
    padding: bytes

    def __post_init__(self) -> None:
        if len(self.header) != ALBUM_PIC_CONTAINER_HEADER_BYTES:
            raise ValueError("ALBUM.PIC container header must be 512 bytes")

        if len(self.pixels) != ALBUM_PIC_CONTAINER_PAYLOAD_BYTES:
            raise ValueError("ALBUM.PIC container payload must be 150x150 RGB565")

        if any(self.padding):
            raise ValueError("ALBUM.PIC container padding must be zero")

        if self.header[: len(ALBUM_PIC_CONTAINER_MAGIC)] != ALBUM_PIC_CONTAINER_MAGIC:
            raise ValueError("ALBUM.PIC container has an unexpected magic")

        marker_end = ALBUM_PIC_CONTAINER_MARKER_OFFSET + len(ALBUM_PIC_CONTAINER_MARKER)
        if self.header[ALBUM_PIC_CONTAINER_MARKER_OFFSET:marker_end] != ALBUM_PIC_CONTAINER_MARKER:
            raise ValueError("ALBUM.PIC container has an unexpected header marker")

    @property
    def width(self) -> int:
        return 150

    @property
    def height(self) -> int:
        return 150

    def encode(self) -> bytes:
        """Reassemble the observed container without changing any bytes."""
        return self.header + self.pixels + self.padding


def decode_album_pic(data: bytes) -> AlbumPicRecord:
    """Decode one exact ``ALBUM.PIC`` record without rendering it."""
    if len(data) < ALBUM_PIC_HEADER_BYTES:
        raise ValueError("ALBUM.PIC record is shorter than its header")

    try:
        status = data[:2].decode("ascii")

    except UnicodeDecodeError as exception:
        raise ValueError("ALBUM.PIC status is not ASCII") from exception

    record = AlbumPicRecord(
        status=status,
        x=data[2],
        y=data[3],
        width=data[4],
        height=data[5],
        pixels=data[ALBUM_PIC_HEADER_BYTES:],
    )

    return record


def decode_album_pic_container(data: bytes) -> AlbumPicContainer:
    """Decode the observed device ``ALBUM.PIC`` container boundary.

    This is intentionally separate from :func:`decode_album_pic`: the device
    file starts with an ``ALBUM`` container header, while the decompiled cache
    writer's successful record starts with ``OK`` plus four geometry bytes.
    """
    if len(data) != ALBUM_PIC_CONTAINER_BYTES:
        raise ValueError(
            "ALBUM.PIC container must contain exactly 250 512-byte sectors"
        )

    payload_start = ALBUM_PIC_CONTAINER_HEADER_BYTES
    payload_end = payload_start + ALBUM_PIC_CONTAINER_PAYLOAD_BYTES
    container = AlbumPicContainer(
        header=data[:payload_start],
        pixels=data[payload_start:payload_end],
        padding=data[payload_end:],
    )

    return container
