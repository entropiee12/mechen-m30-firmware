import unittest

from sleep_timer_delta_probe import (
    EXPECTED_DIFF_FILE_END,
    EXPECTED_DIFF_FILE_START,
    differing_offsets,
    verify_delta,
    virtual_span,
)


class SleepTimerDeltaProbeTests(unittest.TestCase):
    def test_expected_delta_span_is_mapped_to_the_documented_virtual_range(self):
        before = bytes(0x1C00)
        after_data = bytearray(before)
        for offset in range(EXPECTED_DIFF_FILE_START, EXPECTED_DIFF_FILE_START + 337):
            after_data[offset] = 1
        after_data[EXPECTED_DIFF_FILE_END] = 2

        offsets = verify_delta(before, bytes(after_data))

        self.assertEqual(len(offsets), 338)
        self.assertEqual(virtual_span(offsets), (0x40054F98, 0x40055383))

    def test_differing_offsets_reject_members_with_different_lengths(self):
        with self.assertRaises(AssertionError):
            differing_offsets(b"before", b"after-with-different-length")

    def test_delta_rejects_a_change_outside_the_known_span(self):
        before = bytes(0x1C00)
        after_data = bytearray(before)
        for offset in range(EXPECTED_DIFF_FILE_START, EXPECTED_DIFF_FILE_START + 337):
            after_data[offset] = 1
        after_data[EXPECTED_DIFF_FILE_END] = 2
        after_data[0x1000] = 3

        with self.assertRaises(AssertionError):
            verify_delta(before, bytes(after_data))


if __name__ == "__main__":
    unittest.main()
