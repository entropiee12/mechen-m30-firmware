import unittest

from mmm_bp_model import (
    FADE_LEVEL_STEP,
    FADE_PHASE_INTERVAL,
    REFILL_BLOCK_BYTES,
    advance_fade_step,
    fade_mode_name,
    plan_ring_read,
    read_ring_bytes,
)


class MmmBpBufferTests(unittest.TestCase):
    def test_refill_contract_is_512_bytes(self):
        self.assertEqual(REFILL_BLOCK_BYTES, 0x200)

    def test_ring_read_without_wrap(self):
        plan = plan_ring_read(read_offset=4, capacity=32, available=20, requested=12)
        self.assertEqual((plan.first_offset, plan.first_length), (4, 12))
        self.assertEqual((plan.second_offset, plan.second_length), (None, 0))

    def test_ring_read_splits_at_end(self):
        plan = plan_ring_read(read_offset=28, capacity=32, available=8, requested=8)
        self.assertEqual((plan.first_offset, plan.first_length), (28, 4))
        self.assertEqual((plan.second_offset, plan.second_length), (0, 4))

    def test_reader_rejects_short_buffer(self):
        with self.assertRaises(ValueError):
            plan_ring_read(read_offset=0, capacity=32, available=3, requested=4)

    def test_ring_read_returns_wrapped_source_bytes(self):
        source = bytes(range(32))

        payload = read_ring_bytes(source, read_offset=28, available=8, requested=8)

        self.assertEqual(payload, bytes((28, 29, 30, 31, 0, 1, 2, 3)))

    def test_ring_read_rejects_source_shorter_than_declared_capacity(self):
        with self.assertRaises(ValueError):
            read_ring_bytes(bytes(0), read_offset=0, available=0, requested=0)

    def test_fade_mode_names_are_conservative(self):
        self.assertEqual(fade_mode_name(2), "slow")
        self.assertEqual(fade_mode_name(3), "fast")
        self.assertEqual(fade_mode_name(1), "inactive")

    def test_slow_fade_advances_level_every_60_steps(self):
        plan = advance_fade_step(2, FADE_PHASE_INTERVAL - 1, 99, 10000)
        self.assertEqual((plan.mode, plan.step_count, plan.phase), (2, 60, 1))
        self.assertEqual(plan.level, 10000 - FADE_LEVEL_STEP)
        self.assertTrue(plan.callback_due)

    def test_direction_change_resets_the_step_counter(self):
        plan = advance_fade_step(2, 59, 100, 10000)
        self.assertEqual((plan.mode, plan.step_count, plan.phase), (3, 0, 0))
        self.assertEqual(plan.level, 10000)
        self.assertFalse(plan.callback_due)

    def test_fast_fade_uses_the_positive_level_delta(self):
        plan = advance_fade_step(3, 119, 100, 2000)
        self.assertEqual((plan.mode, plan.step_count, plan.phase), (3, 120, 2))
        self.assertEqual(plan.level, 2000 + 2 * FADE_LEVEL_STEP)
        self.assertTrue(plan.callback_due)


if __name__ == "__main__":
    unittest.main()
