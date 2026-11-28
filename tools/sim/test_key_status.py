import unittest

from key_status import (
    ADC_LEVEL_THRESHOLDS,
    ADC_SAMPLE_INTERVAL_CALLS,
    ADC_SAMPLES_PER_STATUS_UPDATE,
    KeyStatusRegisters,
    KeyStatusSampler,
    classify_adc_level,
)


class KeyStatusLevelTests(unittest.TestCase):
    def test_thresholds_match_every_boundary_from_the_firmware_probe(self):
        expected_ranges = (
            (0, 80, 0),
            (81, 88, 1),
            (89, 93, 2),
            (94, 95, 3),
            (96, 103, 4),
            (104, 107, 5),
            (108, 127, 6),
        )

        for start, end, expected_level in expected_ranges:
            with self.subTest(start=start, end=end):
                for average in (start, end):
                    self.assertEqual(classify_adc_level(average), expected_level)

    def test_threshold_table_is_the_six_observed_level_edges(self):
        self.assertEqual(ADC_LEVEL_THRESHOLDS, (81, 89, 94, 96, 104, 108))

    def test_rejects_values_outside_seven_bit_adc_range(self):
        with self.assertRaises(ValueError):
            classify_adc_level(-1)

        with self.assertRaises(ValueError):
            classify_adc_level(128)


class KeyStatusSamplerTests(unittest.TestCase):
    def complete_batch(self, sampler, registers):
        reading = None
        for _ in range(ADC_SAMPLE_INTERVAL_CALLS * ADC_SAMPLES_PER_STATUS_UPDATE):
            reading = sampler.tick(registers)

        return reading

    def test_samples_every_five_calls_and_updates_after_ten_samples(self):
        sampler = KeyStatusSampler()
        registers = KeyStatusRegisters(adc_sample=85, device_status=0x100)

        for invocation in range(1, 50):
            reading = sampler.tick(registers)

            self.assertIsNone(reading, f"status updated too early at call {invocation}")

        reading = sampler.tick(registers)

        self.assertEqual(reading.status, 1)
        self.assertEqual(reading.adc_average, 85)
        self.assertEqual(reading.adc_level, 1)
        self.assertEqual(reading.samples, (85,) * 10)

    def test_second_batch_replaces_the_sample_window_before_status_changes(self):
        sampler = KeyStatusSampler()
        registers = KeyStatusRegisters(adc_sample=85, device_status=0x100)
        first_reading = self.complete_batch(sampler, registers)

        self.assertEqual(first_reading.status, 1)

        registers.adc_sample = 50
        registers.device_status = 0x200
        registers.selector_state = 0x28
        registers.lradc_control = 0x8123

        for invocation in range(1, 50):
            reading = sampler.tick(registers)

            self.assertIsNone(reading, f"second status update arrived at call {invocation}")
            self.assertEqual(sampler.status, 1)

        second_reading = sampler.tick(registers)

        self.assertEqual(second_reading.status, 0)
        self.assertEqual(second_reading.samples, (50,) * 10)
        self.assertEqual(second_reading.adc_average, 50)
        self.assertEqual(second_reading.adc_level, 0)
        self.assertEqual(registers.selector_state, 0x08)
        self.assertEqual(registers.lradc_control, 0x0123)

    def test_status_zero_clears_only_the_observed_control_bits(self):
        sampler = KeyStatusSampler()
        registers = KeyStatusRegisters(
            adc_sample=85,
            device_status=0x200,
            selector_state=0x28,
            lradc_control=0x8123,
        )

        reading = self.complete_batch(sampler, registers)

        self.assertEqual(reading.status, 0)
        self.assertEqual(registers.selector_state, 0x08)
        self.assertEqual(registers.lradc_control, 0x0123)
        self.assertEqual(registers.control_a, 0)
        self.assertEqual(registers.control_b, 0)

    def test_status_one_sets_control_bits_and_output_state(self):
        sampler = KeyStatusSampler()
        registers = KeyStatusRegisters(
            adc_sample=85,
            device_status=0x100,
            selector_state=0x04,
            control_a=0x100,
            control_b=0x400,
        )

        reading = self.complete_batch(sampler, registers)

        self.assertEqual(reading.status, 1)
        self.assertEqual(registers.selector_state, 0x24)
        self.assertEqual(registers.control_a, 0x108)
        self.assertEqual(registers.control_b, 0x420)

    def test_status_two_uses_the_selector_bit_and_clears_output_state(self):
        sampler = KeyStatusSampler()
        registers = KeyStatusRegisters(
            adc_sample=85,
            device_status=0x80,
            selector_state=0x28,
        )

        reading = self.complete_batch(sampler, registers)

        self.assertEqual(reading.status, 2)
        self.assertEqual(registers.selector_state, 0x08)
        self.assertEqual(registers.control_a, 0x08)
        self.assertEqual(registers.control_b, 0x20)

    def test_adc_sample_is_masked_before_level_classification(self):
        sampler = KeyStatusSampler()
        registers = KeyStatusRegisters(adc_sample=0xD5, device_status=0)

        reading = self.complete_batch(sampler, registers)

        self.assertEqual(reading.samples, (85,) * 10)
        self.assertEqual(reading.adc_average, 85)
        self.assertEqual(reading.adc_level, 1)

    def test_adc_average_uses_integer_truncation(self):
        sampler = KeyStatusSampler()
        registers = KeyStatusRegisters(device_status=0)
        reading = None

        for sample in [80] * 9 + [85]:
            registers.adc_sample = sample
            for _ in range(ADC_SAMPLE_INTERVAL_CALLS):
                reading = sampler.tick(registers)

        self.assertEqual(reading.adc_average, 80)
        self.assertEqual(reading.adc_level, 0)

    def test_registers_must_fit_unsigned_32_bit_words(self):
        with self.assertRaises(ValueError):
            KeyStatusRegisters(device_status=-1)

        registers = KeyStatusRegisters()
        registers.selector_state = 0x1_0000_0000
        with self.assertRaises(ValueError):
            KeyStatusSampler().tick(registers)


if __name__ == "__main__":
    unittest.main()
