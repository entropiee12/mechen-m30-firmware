import unittest

from scroll_accel import (
    FAST2_N,
    FAST3_N,
    FAST4_N,
    GAP_MS,
    NEXT,
    PREVIOUS,
    ScrollAccelerator,
)


class ScrollAccelerationTests(unittest.TestCase):
    def test_burst_reaches_the_same_gears_as_the_mips_blob(self):
        accelerator = ScrollAccelerator()
        times = [index * 100 for index in range(41)]
        steps = [accelerator.steps_for(NEXT, now) for now in times]
        self.assertEqual(steps[:5], [1, 1, 1, 1, 1])
        self.assertEqual(steps[5], 5)
        self.assertEqual(steps[FAST2_N - 1], 20)
        self.assertEqual(steps[25], 50)
        self.assertEqual(steps[39], 100)
        self.assertGreater(FAST3_N, FAST2_N)
        self.assertGreater(FAST4_N, FAST3_N)

    def test_pause_and_direction_change_reset_the_burst(self):
        accelerator = ScrollAccelerator()
        for now in (0, 100, 200, 300, 400, 500):
            accelerator.steps_for(NEXT, now)
        self.assertEqual(accelerator.steps_for(NEXT, 600), 5)
        self.assertEqual(accelerator.steps_for(NEXT, 600 + GAP_MS + 1), 1)
        self.assertEqual(accelerator.steps_for(PREVIOUS, 600 + GAP_MS + 2), 1)

    def test_up_down_repeat_rate_boundary_is_explicit(self):
        at_boundary = ScrollAccelerator()
        over_boundary = ScrollAccelerator()
        boundary_steps = [at_boundary.steps_for(NEXT, index * GAP_MS) for index in range(10)]
        over_steps = [over_boundary.steps_for(NEXT, index * (GAP_MS + 1)) for index in range(10)]
        self.assertEqual(boundary_steps, [1, 1, 5, 5, 20, 20, 50, 50, 50, 100])
        self.assertEqual(set(over_steps), {1})

    def test_multi_step_move_sets_the_stock_page_reload_contract(self):
        accelerator = ScrollAccelerator()
        for now in range(0, 1600, 100):
            accelerator.steps_for(NEXT, now)
        result = accelerator.move(10, 1000, NEXT, 1600)
        self.assertEqual(result.requested_steps, 20)
        self.assertEqual(result.consumed_steps, 20)
        self.assertEqual(result.active, 30)
        self.assertTrue(result.page_reload)
        self.assertEqual((result.mode, result.update), (4, 2))

    def test_boundary_burst_does_not_run_past_the_list(self):
        next_accelerator = ScrollAccelerator()
        previous_accelerator = ScrollAccelerator()
        for now in range(0, 1600, 100):
            next_accelerator.steps_for(NEXT, now)
            previous_accelerator.steps_for(PREVIOUS, now)
        next_result = next_accelerator.move(99, 100, NEXT, 1600)
        previous_result = previous_accelerator.move(2, 100, PREVIOUS, 1600)
        self.assertEqual(next_result.active, 100)
        self.assertEqual(next_result.consumed_steps, 1)
        self.assertFalse(next_result.page_reload)
        self.assertEqual(previous_result.active, 1)
        self.assertEqual(previous_result.consumed_steps, 1)
        self.assertFalse(previous_result.page_reload)

    def test_single_step_matches_stock_wrap_at_both_ends(self):
        self.assertEqual(ScrollAccelerator.move_selection(100, 100, NEXT, 1), (1, 1))
        self.assertEqual(ScrollAccelerator.move_selection(1, 100, PREVIOUS, 1), (100, 1))


if __name__ == "__main__":
    unittest.main()
