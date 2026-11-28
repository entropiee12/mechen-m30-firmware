import unittest

from key_driver import (
    ADC_THRESHOLDS,
    KeyDriverRepeatState,
    classify_adc_sample,
)
from key_filter import KEY_DOWN, KEY_HOLD, KEY_LONG_PRESS, KEY_RELEASE


class KeyDriverTests(unittest.TestCase):
    def setUp(self) -> None:
        self.normalized_values = (1, 2, 3, 4, 5, 6, 7)

    def test_thresholds_select_the_first_greater_entry(self):
        result = classify_adc_sample(ADC_THRESHOLDS[2] - 1, self.normalized_values)

        self.assertEqual(result.sampled_value, ADC_THRESHOLDS[2] - 1)
        self.assertEqual(result.normalized_value, 3)
        self.assertFalse(result.override_applied)

    def test_sample_is_masked_to_seven_bits_before_classification(self):
        result = classify_adc_sample(0x80 | 0x02, self.normalized_values)

        self.assertEqual(result.sampled_value, 2)
        self.assertEqual(result.normalized_value, 1)

    def test_unmatched_sample_returns_ff(self):
        result = classify_adc_sample(0x7F, self.normalized_values)

        self.assertEqual(result.normalized_value, 0xFF)

    def test_override_uses_an_explicit_external_table_value(self):
        result = classify_adc_sample(
            0x40,
            self.normalized_values,
            override=True,
            override_value=0x22,
        )

        self.assertEqual((result.sampled_value, result.normalized_value), (0, 0x22))
        self.assertTrue(result.override_applied)

    def test_new_value_emits_key_down_and_starts_repeat_count(self):
        state = KeyDriverRepeatState()

        event = state.process(0x12)

        self.assertEqual((event.key_code, event.event_type), (0x12, KEY_DOWN))
        self.assertEqual((state.previous_value, state.repeat_count), (0x12, 1))

    def test_fifth_and_later_identical_values_emit_repeat_events(self):
        state = KeyDriverRepeatState()

        self.assertIsNotNone(state.process(0x12))
        self.assertIsNone(state.process(0x12))
        self.assertIsNone(state.process(0x12))
        self.assertIsNone(state.process(0x12))

        long_press = state.process(0x12)
        hold = state.process(0x12)

        self.assertEqual(long_press.event_type, KEY_LONG_PRESS)
        self.assertEqual(hold.event_type, KEY_HOLD)

    def test_special_values_emit_their_proven_normalized_events(self):
        state = KeyDriverRepeatState(previous_value=0x12, repeat_count=3)

        release = state.process(0x30)
        play = state.process(0x32)
        card = state.process(0x34)

        self.assertEqual((release.key_code, release.event_type), (0x12, KEY_RELEASE))
        self.assertEqual((play.key_code, play.event_type), (0x0C, KEY_DOWN))
        self.assertEqual((card.key_code, card.event_type), (0x0D, KEY_DOWN))
        self.assertEqual((state.previous_value, state.repeat_count), (0, 0))


if __name__ == "__main__":
    unittest.main()
