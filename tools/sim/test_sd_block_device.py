import unittest

from sd_block_device import CardNotPresentError, SECTOR_SIZE, SdCardBlockDevice


class SdCardBlockDeviceTests(unittest.TestCase):
    def test_capacity_uses_the_512_byte_geometry(self) -> None:
        card = SdCardBlockDevice(sector_count=3)

        self.assertEqual(card.capacity_bytes, 3 * SECTOR_SIZE)

    def test_sector_round_trip_uses_exact_512_byte_blocks(self) -> None:
        card = SdCardBlockDevice(sector_count=2)
        payload = bytes((index % 256 for index in range(SECTOR_SIZE)))

        card.write_sector(1, payload)

        self.assertEqual(card.read_sector(1), payload)
        self.assertEqual(len(card.read_sector(0)), SECTOR_SIZE)

    def test_short_or_long_transfers_are_rejected(self) -> None:
        card = SdCardBlockDevice(sector_count=1)

        with self.assertRaises(ValueError):
            card.write_sector(0, bytes(SECTOR_SIZE - 1))
        with self.assertRaises(ValueError):
            card.write_sector(0, bytes(SECTOR_SIZE + 1))

    def test_removed_card_rejects_reads_and_writes(self) -> None:
        card = SdCardBlockDevice(sector_count=1)
        card.remove()

        with self.assertRaises(CardNotPresentError):
            card.read_sector(0)
        with self.assertRaises(CardNotPresentError):
            card.write_sector(0, bytes(SECTOR_SIZE))

        card.insert()
        self.assertEqual(card.read_sector(0), bytes(SECTOR_SIZE))

    def test_sector_bounds_are_checked(self) -> None:
        card = SdCardBlockDevice(sector_count=2)

        with self.assertRaises(IndexError):
            card.read_sector(-1)
        with self.assertRaises(IndexError):
            card.read_sector(2)


if __name__ == "__main__":
    unittest.main()
