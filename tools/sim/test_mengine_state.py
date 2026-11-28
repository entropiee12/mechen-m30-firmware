import unittest

from tools.sim.mengine_state import MengineState304


class MengineState304Tests(unittest.TestCase):
    def test_payload_masks_update_the_observed_state_bytes(self):
        state = MengineState304()

        self.assertIsNone(state.field_2e)

        snapshot_requested = state.apply_message(0xA3, status=2)

        self.assertEqual(state.field_2b, 0x20)
        self.assertEqual(state.field_2c, 0x03)
        self.assertEqual(state.field_2e, 0x80)
        self.assertTrue(snapshot_requested)

    def test_status_two_does_not_repeat_transition_when_selection_is_already_three(self):
        state = MengineState304(field_2c=3)

        snapshot_requested = state.apply_message(0x53, status=2)

        self.assertEqual(state.field_2b, 0x10)
        self.assertEqual(state.field_2c, 0x03)
        self.assertEqual(state.field_2e, 0x40)
        self.assertFalse(snapshot_requested)

    def test_status_zero_and_one_do_not_request_the_status_two_snapshot(self):
        for status in (0, 1):
            with self.subTest(status=status):
                state = MengineState304()

                snapshot_requested = state.apply_message(0xA3, status=status)

                self.assertFalse(snapshot_requested)
                self.assertEqual(state.field_2c, 3)

    def test_payload_and_initial_fields_must_be_bytes(self):
        for value in (-1, 0x100):
            with self.subTest(payload=value):
                with self.assertRaises(ValueError):
                    MengineState304().apply_message(value, status=2)

        with self.assertRaises(ValueError):
            MengineState304(field_2e=0x100)

    def test_unobserved_status_is_rejected(self):
        with self.assertRaises(ValueError):
            MengineState304().apply_message(0xA3, status=3)


if __name__ == "__main__":
    unittest.main()
