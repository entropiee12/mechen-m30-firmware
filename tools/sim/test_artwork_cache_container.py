import unittest

from artwork_cache import (
    ALBUM_PIC_CONTAINER_BYTES,
    ALBUM_PIC_CONTAINER_HEADER_BYTES,
    ALBUM_PIC_CONTAINER_MARKER_OFFSET,
    ALBUM_PIC_CONTAINER_PAYLOAD_BYTES,
    AlbumPicContainer,
    decode_album_pic_container,
)


def make_container() -> bytes:
    data = bytearray(ALBUM_PIC_CONTAINER_BYTES)
    data[:5] = b"ALBUM"
    data[ALBUM_PIC_CONTAINER_MARKER_OFFSET : ALBUM_PIC_CONTAINER_MARKER_OFFSET + 3] = b"NOA"
    data[0x1FE:0x200] = b"\x55\xAA"

    payload_start = ALBUM_PIC_CONTAINER_HEADER_BYTES
    payload_end = payload_start + ALBUM_PIC_CONTAINER_PAYLOAD_BYTES
    data[payload_start:payload_end] = bytes(
        value % 256 for value in range(ALBUM_PIC_CONTAINER_PAYLOAD_BYTES)
    )

    return bytes(data)


class AlbumPicContainerTests(unittest.TestCase):
    def test_observed_container_round_trips_without_interpreting_pixels(self):
        container = decode_album_pic_container(make_container())

        self.assertEqual((container.width, container.height), (150, 150))
        self.assertEqual(len(container.pixels), ALBUM_PIC_CONTAINER_PAYLOAD_BYTES)
        self.assertEqual(container.encode(), make_container())

    def test_container_rejects_wrong_size_or_header(self):
        with self.assertRaises(ValueError):
            decode_album_pic_container(bytes(ALBUM_PIC_CONTAINER_BYTES - 1))

        wrong_magic = bytearray(make_container())
        wrong_magic[:5] = b"OTHER"
        with self.assertRaises(ValueError):
            decode_album_pic_container(bytes(wrong_magic))

    def test_container_rejects_nonzero_padding(self):
        data = bytearray(make_container())
        data[-1] = 1

        with self.assertRaises(ValueError):
            decode_album_pic_container(bytes(data))

    def test_container_header_marker_is_required(self):
        data = bytearray(make_container())
        data[
            ALBUM_PIC_CONTAINER_MARKER_OFFSET : ALBUM_PIC_CONTAINER_MARKER_OFFSET + 3
        ] = b"BAD"

        with self.assertRaises(ValueError):
            AlbumPicContainer(
                header=bytes(data[:ALBUM_PIC_CONTAINER_HEADER_BYTES]),
                pixels=bytes(
                    data[
                        ALBUM_PIC_CONTAINER_HEADER_BYTES : ALBUM_PIC_CONTAINER_HEADER_BYTES
                        + ALBUM_PIC_CONTAINER_PAYLOAD_BYTES
                    ]
                ),
                padding=bytes(data[ALBUM_PIC_CONTAINER_HEADER_BYTES + ALBUM_PIC_CONTAINER_PAYLOAD_BYTES :]),
            )


if __name__ == "__main__":
    unittest.main()
