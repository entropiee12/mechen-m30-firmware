import hashlib
import unittest
from pathlib import Path

from driver_bank_table import GROUP_A, GROUP_B, UnitBankImage


FIRMWARE = Path(__file__).resolve().parents[2] / "analysis" / "community" / "fw"
AUD_DEV_SHA256 = "3349287198faaa90a503353673674d5628e85711db9ee16217db5a803484f09c"
KERNEL_SHA256 = "58f9d0c62bdbdab7cfeee1c5e65bbe8b88ee962f8eecd3dd0515d1a1056d8bd6"


def load_member(name: str, expected_sha256: str) -> bytes:
    path = FIRMWARE / name
    if not path.is_file():
        raise unittest.SkipTest(f"{name} is not available")

    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != expected_sha256:
        raise unittest.SkipTest(f"{name} has an unexpected SHA-256")

    return data


class SyntheticTableTests(unittest.TestCase):
    def build(self, entries_b: list[int], base_b: int = 0x400) -> bytes:
        data = bytearray(0x400 + 0x1000)
        data[4:8] = b"yqhx"
        data[0x4C:0x50] = base_b.to_bytes(4, "little")
        for index, entry in enumerate(entries_b):
            data[0x100 + index * 2 : 0x102 + index * 2] = entry.to_bytes(2, "little")

        return bytes(data)

    def test_decodes_contiguous_units(self):
        image = UnitBankImage(self.build([0x3000, 0x2003]))
        first, second = image.banks(GROUP_B)
        self.assertEqual((first.file_start, first.size), (0x400, 0x300))
        self.assertEqual((second.file_start, second.size), (0x700, 0x200))
        self.assertEqual(second.window, 0x9400)

    def test_rejects_gap_between_banks(self):
        with self.assertRaisesRegex(ValueError, "expected 0x3"):
            UnitBankImage(self.build([0x3000, 0x2004]))

    def test_rejects_bank_beyond_image(self):
        with self.assertRaisesRegex(ValueError, "exceeds"):
            UnitBankImage(self.build([0xF000, 0xFF10], base_b=0x1000))

    def test_pointer_outside_bank_has_no_file_offset(self):
        image = UnitBankImage(self.build([0x3000]))
        target = image.resolve(0x2C009A01)
        self.assertIsNone(target.file_offset)
        self.assertIsNone(target.bank)
        self.assertTrue(target.mips16)

    def test_rejects_pointer_without_group(self):
        image = UnitBankImage(self.build([0x3000]))
        with self.assertRaises(ValueError):
            image.resolve(0xBFC08000)


class AudDevTableTests(unittest.TestCase):
    def test_bank_five_matches_the_executed_idx18_code(self):
        image = UnitBankImage(load_member("aud_dev.drv", AUD_DEV_SHA256))
        bank = image.bank(GROUP_B, 5)
        self.assertEqual((bank.file_start, bank.size), (0x1F00, 0x600))
        target = image.resolve(0x2C149401)
        self.assertEqual(target.file_offset, 0x1F00)
        self.assertEqual(target.window_address, 0x9401)
        self.assertTrue(target.mips16)


class KernelTableTests(unittest.TestCase):
    def test_tables_are_contiguous_and_end_at_the_file_end(self):
        data = load_member("kernel.drv", KERNEL_SHA256)
        image = UnitBankImage(data)
        group_a = image.banks(GROUP_A)
        group_b = image.banks(GROUP_B)
        self.assertEqual(len(group_a), 9)
        self.assertEqual(len(group_b), 9)
        self.assertEqual(group_a[-1].file_end, group_b[0].file_start)
        self.assertEqual(group_b[-1].file_end, len(data))

    def test_shared_memory_entry_lands_on_a_mips16_extended_save(self):
        data = load_member("kernel.drv", KERNEL_SHA256)
        image = UnitBankImage(data)
        target = image.resolve(0x1F189175)
        self.assertEqual(target.group, GROUP_A)
        self.assertEqual(target.bank_index, 6)
        self.assertEqual(target.file_offset, 0x1B74)
        self.assertEqual(data[0x1B74:0x1B78].hex(), "08f0f564")

    def test_task_create_entry_lands_on_a_mips16_save(self):
        data = load_member("kernel.drv", KERNEL_SHA256)
        image = UnitBankImage(data)
        target = image.resolve(0x2F009945)
        self.assertEqual(target.group, GROUP_B)
        self.assertEqual(target.bank_index, 0)
        self.assertEqual(target.file_offset, 0x2944)
        self.assertEqual(data[0x2944:0x2946].hex(), "f764")


if __name__ == "__main__":
    unittest.main()
