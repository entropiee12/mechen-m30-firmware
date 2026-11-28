import unittest

from auddev_driver import (
    MMIO_C0000000,
    MMIO_C0000110,
    MMIO_C000100C,
    MMIO_C0050000,
    MMIO_C0050004,
    MMIO_C005001C,
    MMIO_C0051000,
    MMIO_C0051010,
    MMIO_C0052000,
    MMIO_C0090060,
    execute_operation,
)


U32_MASK = 0xFFFFFFFF


def words_for_operation_4():
    return {
        MMIO_C0000000: 0x12000045,
        MMIO_C0050000: 0x80000004,
        MMIO_C005001C: 0x123456FF,
        MMIO_C0050004: 0xA5A5F3F1,
        0xC0050020: 0xDEADBEEF,
    }


def words_for_operation_6(mode: int = 0):
    return {
        MMIO_C0052000: mode,
        MMIO_C0000110: 0x12345678,
        MMIO_C000100C: 0xABCDEF80,
        0xC0001010: 0xDEADBEEF,
    }


def words_for_operation_8():
    return {
        MMIO_C0051010: 0x81234567,
        MMIO_C0090060: 0x87654323,
        MMIO_C0051000: 0x12345678,
        0xC0051020: 0xDEADBEEF,
    }


class WriteRecordingDict(dict):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.updated_keys = []

    def update(self, other=(), **kwargs):
        values = dict(other, **kwargs)
        self.updated_keys.extend(values)
        super().update(values)


class AuddevDriverTests(unittest.TestCase):
    def test_operation_4_zero_second_argument_branch(self):
        mmio = words_for_operation_4()
        original_unrelated = mmio[0xC0050020]

        result = execute_operation(4, 3, 0, mmio)

        self.assertEqual(result, 0)
        self.assertEqual(mmio[MMIO_C0000000], 0x12000845)
        self.assertEqual(mmio[MMIO_C0050000], 0x80000015)
        self.assertEqual(mmio[MMIO_C005001C], 0x1234567F)
        self.assertEqual(mmio[MMIO_C0050004], 0xA5A5F3F1)
        self.assertEqual(mmio[0xC0050020], original_unrelated)

    def test_operation_4_nonzero_second_argument_branch(self):
        mmio = words_for_operation_4()
        original_unrelated = mmio[0xC0050020]

        result = execute_operation(4, 2, 1, mmio)

        self.assertEqual(result, 0)
        self.assertEqual(mmio[MMIO_C0000000], 0x12000845)
        self.assertEqual(mmio[MMIO_C0050000], 0x80000025)
        self.assertEqual(mmio[MMIO_C005001C], 0x1234567F)
        self.assertEqual(mmio[MMIO_C0050004], 0xA5A5E3F1)
        self.assertEqual(mmio[0xC0050020], original_unrelated)

    def test_operation_4_shift_uses_32_bit_wrapping(self):
        mmio = words_for_operation_4()

        execute_operation(4, U32_MASK, 0, mmio)

        expected = (((0xA5A5F3F1 & 0xFFFFFFCF) | 0xFFFFFFF0) & 0xFFFFFFFE) | 1
        self.assertEqual(mmio[MMIO_C0050004], expected)

    def test_operation_6_selectors_cover_each_firmware_rate_branch(self):
        cases = (
            (0x08, 6, 6),
            (0x0B, 5, 2),
            (0x0C, 5, 6),
            (0x10, 4, 6),
            (0x16, 3, 2),
            (0x18, 3, 6),
            (0x20, 2, 6),
            (0x2C, 1, 2),
            (0x30, 1, 6),
            (0x60, 0, 6),
            (0xC0, 0, 6),
            (0x07, 1, 6),
            (0xFFFFFFFF, 1, 6),
        )

        for rate_code, expected_selector, expected_clock_nibble in cases:
            with self.subTest(rate_code=rate_code):
                mmio = words_for_operation_6()
                result = execute_operation(6, rate_code, 0, mmio)

                self.assertEqual(result, 0)
                self.assertEqual(
                    mmio[MMIO_C000100C],
                    (0xABCDEF80 & 0xFFFFFFF8) | expected_selector,
                )
                self.assertEqual(
                    mmio[MMIO_C0000110],
                    (0x12345678 & 0xFFFFFFF0) | expected_clock_nibble,
                )

    def test_operation_6_mode_bit_adjusts_each_selector_class(self):
        cases = (
            (0x08, 1, 6),
            (0x0B, 7, 2),
            (0x0C, 7, 6),
            (0x10, 6, 6),
            (0x16, 5, 2),
            (0x18, 5, 6),
            (0x20, 4, 6),
            (0x2C, 3, 2),
            (0x30, 3, 6),
            (0x60, 1, 6),
            (0x07, 3, 6),
        )

        for rate_code, expected_selector, expected_clock_nibble in cases:
            with self.subTest(rate_code=rate_code):
                mmio = words_for_operation_6(mode=1)
                result = execute_operation(6, rate_code, 0, mmio)

                self.assertEqual(result, 0)
                self.assertEqual(
                    mmio[MMIO_C000100C],
                    (0xABCDEF80 & 0xFFFFFFF8) | expected_selector,
                )
                self.assertEqual(
                    mmio[MMIO_C0000110],
                    (0x12345678 & 0xFFFFFFF0) | expected_clock_nibble,
                )

    def test_operation_6_special_high_rate_skips_mode_adjustment(self):
        mmio = words_for_operation_6(mode=1)

        result = execute_operation(6, 0xC0, 0, mmio)

        self.assertEqual(result, 0)
        self.assertEqual(mmio[MMIO_C000100C], 0xABCDEF80)
        self.assertEqual(mmio[MMIO_C0000110], 0x12345676)

    def test_operation_6_skips_selector_store_when_value_is_unchanged(self):
        mmio = WriteRecordingDict(words_for_operation_6())
        mmio.updated_keys.clear()

        execute_operation(6, 0x60, 0, mmio)

        self.assertIn(MMIO_C0000110, mmio.updated_keys)
        self.assertNotIn(MMIO_C000100C, mmio.updated_keys)

    def test_operation_8_low_modes_apply_low_field_formula(self):
        cases = ((0, 0x12), (1, 0xA5))

        for mode, argument in cases:
            with self.subTest(mode=mode):
                mmio = words_for_operation_8()
                result = execute_operation(8, mode, argument, mmio)

                expected = 0x81234567 | 0x04000000
                expected &= 0xFFFFFFF3
                expected &= 0xFFFFCFFF
                expected |= 0x180
                expected &= 0xFFFFF1FF
                expected |= (argument << 9) & U32_MASK

                self.assertEqual(result, 0)
                self.assertEqual(mmio[MMIO_C0051010], expected & U32_MASK)
                self.assertEqual(mmio[MMIO_C0090060], 0x87654323)
                self.assertEqual(mmio[MMIO_C0051000], 0x12345678)

    def test_operation_8_mode_two_sets_bit_fifteen(self):
        mmio = words_for_operation_8()

        result = execute_operation(8, 2, 0x35, mmio)

        expected = 0x81234567 | 0x04000000
        expected &= 0xFFFFFFF3
        expected &= 0xFFFFFE7F
        expected |= 0x3000
        expected &= 0xFFF8FFFF
        expected |= (0x35 << 16) & U32_MASK
        expected |= 0x8000

        self.assertEqual(result, 0)
        self.assertEqual(mmio[MMIO_C0051010], expected & U32_MASK)

    def test_operation_8_mode_three_clears_bit_fifteen_and_returns(self):
        mmio = words_for_operation_8()

        result = execute_operation(8, 3, 0x35, mmio)

        expected = 0x81234567 | 0x04000000
        expected &= 0xFFFFFFF3
        expected &= 0xFFFFFE7F
        expected |= 0x3000
        expected &= 0xFFF8FFFF
        expected |= (0x35 << 16) & U32_MASK
        expected &= 0xFFFF7FFF

        self.assertEqual(result, 0)
        self.assertEqual(mmio[MMIO_C0051010], expected & U32_MASK)
        self.assertEqual(mmio[MMIO_C0090060], 0x87654323)
        self.assertEqual(mmio[MMIO_C0051000], 0x12345678)

    def test_operation_8_mode_four_uses_its_two_registers_only(self):
        mmio = {
            MMIO_C0090060: 0x87654323,
            MMIO_C0051000: 0x12345678,
            0xC0051020: 0xDEADBEEF,
        }

        result = execute_operation(8, 4, 3, mmio)

        expected_c0090060 = (((0x87654323 & 0xFFFFFFFC) & 0xFFFFFFF3) | 5)
        expected_c0051000 = 0x12345678 & 0xFFFFFBFF
        expected_c0051000 |= 0x400
        expected_c0051000 |= 3 << 11
        expected_c0051000 |= 0x300

        self.assertEqual(result, 0)
        self.assertEqual(mmio[MMIO_C0090060], expected_c0090060)
        self.assertEqual(mmio[MMIO_C0051000], expected_c0051000)
        self.assertEqual(mmio[0xC0051020], 0xDEADBEEF)
        self.assertNotIn(MMIO_C0051010, mmio)

    def test_operation_8_other_mode_only_sets_the_common_bit(self):
        mmio = words_for_operation_8()

        result = execute_operation(8, 5, 0x12345678, mmio)

        self.assertEqual(result, 0)
        self.assertEqual(mmio[MMIO_C0051010], 0x85234567)
        self.assertEqual(mmio[MMIO_C0090060], 0x87654323)
        self.assertEqual(mmio[MMIO_C0051000], 0x12345678)

    def test_operation_8_shift_wraps_to_a_32_bit_word(self):
        mmio = words_for_operation_8()

        execute_operation(8, 0, U32_MASK, mmio)

        expected = 0x81234567 | 0x04000000
        expected &= 0xFFFFFFF3
        expected &= 0xFFFFCFFF
        expected |= 0x180
        expected &= 0xFFFFF1FF
        expected |= (U32_MASK << 9) & U32_MASK
        self.assertEqual(mmio[MMIO_C0051010], expected & U32_MASK)

    def test_unknown_operation_returns_none_without_touching_mmio(self):
        mmio = {0xC0051010: 0x12345678}
        original = dict(mmio)

        result = execute_operation(7, 0, 0, mmio)

        self.assertIsNone(result)
        self.assertEqual(mmio, original)

    def test_missing_required_register_fails_without_partial_writes(self):
        mmio = {
            MMIO_C0000000: 0,
            MMIO_C0050000: 0,
            MMIO_C005001C: 0,
        }
        original = dict(mmio)

        with self.assertRaises(KeyError):
            execute_operation(4, 1, 0, mmio)

        self.assertEqual(mmio, original)

    def test_invalid_argument_fails_without_writes(self):
        mmio = words_for_operation_4()
        original = dict(mmio)

        with self.assertRaises(ValueError):
            execute_operation(4, -1, 0, mmio)

        self.assertEqual(mmio, original)

    def test_invalid_mmio_word_fails_without_writes(self):
        mmio = words_for_operation_6()
        mmio[MMIO_C0000110] = 0x1_0000_0000
        original = dict(mmio)

        with self.assertRaises(ValueError):
            execute_operation(6, 0x18, 0, mmio)

        self.assertEqual(mmio, original)


if __name__ == "__main__":
    unittest.main()
