import struct
import unittest

from config_bin import read_config_bin_item


class ConfigBinTests(unittest.TestCase):
    def test_reads_payload_using_the_item_id_index(self):
        item_id = 0x116
        payload_offset = 0xA00
        payload = bytes.fromhex("00 00 00 00 01 00 01 00")
        image = bytearray(payload_offset + len(payload))
        index_offset = 0x10 + item_id * 8
        struct.pack_into("<II", image, index_offset, payload_offset, len(payload))
        image[payload_offset:] = payload

        self.assertEqual(read_config_bin_item(bytes(image), item_id), payload)

    def test_rejects_negative_and_non_integer_item_ids(self):
        with self.assertRaises(ValueError):
            read_config_bin_item(bytes(0x100), -1)

        with self.assertRaises(ValueError):
            read_config_bin_item(bytes(0x100), "0x96")

    def test_rejects_truncated_directory_entry(self):
        with self.assertRaises(ValueError):
            read_config_bin_item(bytes(0x100), 0x96)

    def test_rejects_payload_outside_the_image(self):
        image = bytearray(0x500)
        index_offset = 0x10 + 0x96 * 8
        struct.pack_into("<II", image, index_offset, 0x4F0, 0x20)

        with self.assertRaises(ValueError):
            read_config_bin_item(bytes(image), 0x96)


if __name__ == "__main__":
    unittest.main()
