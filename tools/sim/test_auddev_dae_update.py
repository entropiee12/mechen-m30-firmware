import unittest

from auddev_dae_update import BAND_BYTES, AudDevDaeState, apply_dae_update
from dsp_model import DAE_CONFIG_BYTES
from m30_model import build_firmware_eq_structure


def rock_structure(dynamic_range: int) -> bytes:
    built = build_firmware_eq_structure(1, 7, bytes(DAE_CONFIG_BYTES)).structure
    data = bytearray(built)
    data[0x0C] = dynamic_range
    return bytes(data)


class RateGateTests(unittest.TestCase):
    def measure(self, dynamic_range, rate, eq_flag=0, drc_flag=1):
        state = AudDevDaeState(eq_flag, 0, drc_flag, bytes(BAND_BYTES))
        result = apply_dae_update(state, rock_structure(dynamic_range), rate)
        return (result.eq_flag_3a, result.busy_28, result.drc_flag_40)

    def test_probe_matrix_with_dynamic_range_enabled(self):
        self.assertEqual(self.measure(1, 48000), (1, 1, 1))
        self.assertEqual(self.measure(1, 48001), (0, 0, 0))
        self.assertEqual(self.measure(1, 96000), (0, 0, 0))

    def test_probe_matrix_with_dynamic_range_disabled(self):
        self.assertEqual(self.measure(0, 48000), (1, 1, 0))
        self.assertEqual(self.measure(0, 48001), (0, 0, 0))
        self.assertEqual(self.measure(0, 96000), (0, 0, 0))

    def test_previously_enabled_flag_is_cleared_above_48_khz_only(self):
        self.assertEqual(self.measure(0, 48000, eq_flag=1), (1, 1, 0))
        self.assertEqual(self.measure(0, 48001, eq_flag=1), (0, 0, 0))
        self.assertEqual(self.measure(0, 96000, eq_flag=1), (0, 0, 0))

    def test_bands_are_copied_at_every_rate(self):
        structure = rock_structure(0)
        for rate in (48000, 96000):
            with self.subTest(rate=rate):
                result = apply_dae_update(
                    AudDevDaeState(),
                    structure,
                    rate,
                )
                self.assertEqual(result.bands, structure[0x10:0x90])
                self.assertNotEqual(result.bands, bytes(BAND_BYTES))

    def test_band_flag_off_clears_the_eq_flag(self):
        structure = bytearray(rock_structure(0))
        structure[0x07] = 0
        result = apply_dae_update(
            AudDevDaeState(eq_flag_3a=1),
            bytes(structure),
            44100,
        )
        self.assertEqual(result.eq_flag_3a, 0)
        self.assertEqual(result.bands, bytes(BAND_BYTES))


class UnmodeledPathTests(unittest.TestCase):
    def test_busy_entry_state_is_refused(self):
        with self.assertRaises(NotImplementedError):
            apply_dae_update(AudDevDaeState(busy_28=1), rock_structure(0), 44100)

    def test_speaker_compensation_is_refused(self):
        structure = bytearray(rock_structure(0))
        structure[0x08] = 1
        with self.assertRaises(NotImplementedError):
            apply_dae_update(AudDevDaeState(), bytes(structure), 44100)

    def test_band_adjustments_are_refused(self):
        structure = bytearray(rock_structure(0))
        structure[0xC2] = 1
        with self.assertRaises(NotImplementedError):
            apply_dae_update(AudDevDaeState(), bytes(structure), 44100)

    def test_rejects_bad_input_sizes(self):
        with self.assertRaises(ValueError):
            apply_dae_update(AudDevDaeState(), bytes(0x10), 44100)

        with self.assertRaises(ValueError):
            AudDevDaeState(bands=bytes(0x10))


if __name__ == "__main__":
    unittest.main()
