import unittest

from album_pic_evidence_probe import EXPECTED_DEVICE_SHA256, inspect_album_pic
from artwork_cache import ALBUM_PIC_CONTAINER_BYTES


def make_album_pic() -> bytes:
    data = bytearray(ALBUM_PIC_CONTAINER_BYTES)
    data[:5] = b"ALBUM"
    data[0x10:0x13] = b"NOA"
    data[0x200 : 0x200 + 45_000] = bytes(value % 256 for value in range(45_000))

    return bytes(data)


class AlbumPicEvidenceProbeTests(unittest.TestCase):
    def test_probe_accepts_a_container_without_a_fixed_hash(self):
        summary = inspect_album_pic(make_album_pic(), expected_sha256=None)

        self.assertEqual(summary["bytes"], ALBUM_PIC_CONTAINER_BYTES)
        self.assertEqual(summary["width"], 150)
        self.assertEqual(summary["height"], 150)
        self.assertEqual(summary["payload_bytes"], 45_000)
        self.assertEqual(summary["padding_bytes"], 82_488)

    def test_probe_rejects_a_hash_mismatch_before_decoding(self):
        with self.assertRaises(ValueError):
            inspect_album_pic(make_album_pic(), expected_sha256=EXPECTED_DEVICE_SHA256)


if __name__ == "__main__":
    unittest.main()
