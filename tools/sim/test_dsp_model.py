import struct
import tempfile
import unittest
from pathlib import Path

from m30_model import build_firmware_eq_structure
from dsp_model import (
    MMM_MP_DSP_NAMES,
    apply_config_bin_dae_defaults,
    select_aud_dev_dae_groups,
    select_dsp,
    select_initial_mengine_dsp_from_config_bin,
    select_initial_mengine_dsp_from_config_item_96,
    select_mengine_dsp,
)


class DspSelectorTests(unittest.TestCase):
    def test_table_preserves_recovered_firmware_spelling(self):
        with tempfile.TemporaryDirectory() as tmp:
            selection = select_dsp(0, Path(tmp))
        self.assertEqual(selection.firmware_name, "adMP3.dsp")
        self.assertEqual(selection.member_name, "admp3.dsp")
        self.assertEqual(len(MMM_MP_DSP_NAMES), 16)

    def test_missing_member_is_not_executed_or_invented(self):
        with tempfile.TemporaryDirectory() as tmp:
            selection = select_dsp(5, Path(tmp))
        self.assertFalse(selection.available)
        self.assertFalse(selection.payload_executable)
        self.assertIsNone(selection.sha256)

    def test_existing_member_is_header_checked_and_fingerprinted(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "admp3.dsp"
            path.write_bytes(b"yqhx" + struct.pack("<15I", *range(15)))
            selection = select_dsp(0, Path(tmp))
        self.assertTrue(selection.available)
        self.assertEqual(selection.size, 64)
        self.assertEqual(selection.header_words[0], 0x78687179)
        self.assertEqual(len(selection.sha256), 64)

    def test_offset_four_resident_header_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "admp3.dsp"
            path.write_bytes(b"\0\0\0\0yqhx" + bytes(58))
            with self.assertRaises(ValueError):
                select_dsp(0, Path(tmp))

    def test_index_bounds_are_explicit(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                select_dsp(-1, Path(tmp))
            with self.assertRaises(ValueError):
                select_dsp(16, Path(tmp))

    def test_mengine_processing_flag_selects_the_recovered_dsp_members(self):
        with tempfile.TemporaryDirectory() as tmp:
            processing = select_mengine_dsp(True, Path(tmp))
            bypass = select_mengine_dsp(False, Path(tmp))

        self.assertEqual(processing.format_index, 13)
        self.assertEqual(processing.firmware_name, "audiopp.dsp")
        self.assertEqual(bypass.format_index, 14)
        self.assertEqual(bypass.firmware_name, "adppext.dsp")
        self.assertFalse(processing.payload_executable)
        self.assertFalse(bypass.payload_executable)

    def test_mengine_processing_flag_requires_boolean_input(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                select_mengine_dsp(1, Path(tmp))

    def test_initial_config_item_96_default_selects_processing_dsp(self):
        config_item = bytes.fromhex("01 00 02 00 00 00 01 00")

        with tempfile.TemporaryDirectory() as tmp:
            selection = select_initial_mengine_dsp_from_config_item_96(
                config_item,
                Path(tmp),
            )

        self.assertEqual(selection.format_index, 13)
        self.assertEqual(selection.firmware_name, "audiopp.dsp")

    def test_initial_config_item_96_selects_bypass_for_values_other_than_one(self):
        config_item = bytes.fromhex("02 00 02 00 00 00 01 00")

        with tempfile.TemporaryDirectory() as tmp:
            selection = select_initial_mengine_dsp_from_config_item_96(
                config_item,
                Path(tmp),
            )

        self.assertEqual(selection.format_index, 14)
        self.assertEqual(selection.firmware_name, "adppext.dsp")

    def test_initial_config_item_96_requires_eight_bytes(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                select_initial_mengine_dsp_from_config_item_96(
                    bytes(7),
                    Path(tmp),
                )

    def test_config_bin_item_96_default_selects_processing_dsp(self):
        config_bin = bytearray(0xAC6)
        struct.pack_into("<II", config_bin, 0x4C0, 0xABE, 8)
        config_bin[0xABE:0xAC6] = bytes.fromhex("01 00 02 00 00 00 01 00")

        with tempfile.TemporaryDirectory() as tmp:
            selection = select_initial_mengine_dsp_from_config_bin(
                bytes(config_bin),
                Path(tmp),
            )

        self.assertEqual(selection.firmware_name, "audiopp.dsp")


class AudDevDaeGateTests(unittest.TestCase):
    def test_config_initializer_copies_verified_fields_and_preserves_others(self):
        items = {
            0x82: bytes((0x12,)) + bytes(7),
            0x83: bytes((0x83,)) + bytes(7),
            0x84: bytes((0x84,)) + bytes(7),
            0x85: bytes((0x34,)) + bytes(7),
            0x98: bytes(8),
            0xA5: bytes((0x78,)) + bytes(7),
            0xA6: bytes.fromhex("aa bb cc dd 11 22 33 44 55 66 ee ff"),
        }
        for band_index in range(16):
            item_id = 0x86 + band_index
            header = bytes((0xF0, band_index, 0xAA, 0x55))
            band = bytes((band_index * 8 + offset) & 0xFF for offset in range(8))
            items[item_id] = header + band

        config_bin = bytearray(0x800)
        payload_offset = len(config_bin)
        for item_id, payload in sorted(items.items()):
            index_offset = 0x10 + item_id * 8
            struct.pack_into("<II", config_bin, index_offset, payload_offset, len(payload))
            config_bin.extend(payload)
            payload_offset += len(payload)

        initial_configuration = bytes((0xA5,)) * 0xF0
        initialized = apply_config_bin_dae_defaults(
            initial_configuration,
            bytes(config_bin),
        )

        self.assertEqual(initialized[0x03], 0x12)
        self.assertEqual(initialized[0x04:0x07], bytes((0x83, 0, 0)))
        self.assertEqual(initialized[0x07], 0x34)
        self.assertEqual(initialized[0x0C], 0)
        self.assertEqual(initialized[0x0D], 0x78)
        for band_index in range(16):
            structure_offset = 0x10 + band_index * 8
            expected = bytes(
                (band_index * 8 + offset) & 0xFF
                for offset in range(8)
            )
            self.assertEqual(
                initialized[structure_offset : structure_offset + 8],
                expected,
            )

        self.assertEqual(initialized[0xBA:0xC0], bytes.fromhex("11 22 33 44 55 66"))
        self.assertEqual(initialized[0x02], 0xA5)
        self.assertEqual(initialized[0x90], 0xA5)
        self.assertEqual(initialized[0xC0], 0xA5)

    def test_config_initializer_mode_selects_item_84_and_clears_the_other_field(self):
        config_bin = bytearray(0x1000)
        payloads = {
            0x82: bytes((0x02,)) + bytes(7),
            0x83: bytes((0x83,)) + bytes(7),
            0x84: bytes((0x84,)) + bytes(7),
            0x85: bytes((0x01,)) + bytes(7),
            0x98: bytes((0x01,)) + bytes(7),
            0xA5: bytes(8),
            0xA6: bytes(12),
            0x99: bytes.fromhex("00 00 00 00 01 00 02 00 03 00 04 00"),
            0x9A: bytes.fromhex("00 00 00 00 05 00 06 00 07 00 08 00"),
            0x9B: bytes.fromhex("00 00 00 00 09 00 0a 00 0b 00 0c 00"),
            0x9C: bytes.fromhex("0d 00 00 00 00 00 00 00"),
        }
        for band_index in range(16):
            payloads[0x86 + band_index] = bytes(12)

        payload_offset = len(config_bin)
        for item_id, payload in sorted(payloads.items()):
            struct.pack_into("<II", config_bin, 0x10 + item_id * 8, payload_offset, len(payload))
            config_bin.extend(payload)
            payload_offset += len(payload)

        initial = bytearray(0xF0)
        initial[0x02] = 1
        initialized = apply_config_bin_dae_defaults(bytes(initial), bytes(config_bin))

        self.assertEqual(initialized[0x04:0x07], bytes((0, 0x84, 0)))
        # +0x0c is nonzero, so the documented secondary record helper runs.
        self.assertEqual(initialized[0x0D], 1)
        self.assertEqual(initialized[0xE2:0xE4], bytes.fromhex("0d 00"))
        self.assertEqual(initialized[0xA2:0xAA], bytes.fromhex("f3 ff 02 00 03 00 00 00"))
        self.assertEqual(initialized[0x9E:0xA2], bytes.fromhex("04 00 08 00"))

    def test_config_initializer_early_normalization_path_preserves_plus_6(self):
        items = {
            0x82: bytes((1,)) + bytes(7),
            0x85: bytes(8),
            0x98: bytes((1,)) + bytes(7),
            0xA5: bytes(8),
            0xA6: bytes(12),
        }
        for band_index in range(16):
            items[0x86 + band_index] = bytes(12)

        config_bin = bytearray(0x800)
        payload_offset = len(config_bin)
        for item_id, payload in sorted(items.items()):
            struct.pack_into("<II", config_bin, 0x10 + item_id * 8, payload_offset, len(payload))
            config_bin.extend(payload)
            payload_offset += len(payload)

        initial = bytearray(0xF0)
        initial[0x02] = 1
        initial[0x06] = 0x66
        initialized = apply_config_bin_dae_defaults(bytes(initial), bytes(config_bin))

        self.assertEqual(initialized[0x04:0x07], bytes((0, 0, 0x66)))

        initial[0x02] = 0
        initial[0x04:0x07] = bytes((0x44, 0x55, 0x66))
        gated = apply_config_bin_dae_defaults(bytes(initial), bytes(config_bin))
        self.assertEqual(gated[0x04:0x07], bytes((0x44, 0x55, 0x66)))

    def test_config_initializer_rejects_missing_items(self):
        config_bin = bytearray(0x800)
        item_id = 0x82
        struct.pack_into("<II", config_bin, 0x10 + item_id * 8, 0x700, 1)
        config_bin[0x700] = 1

        with self.assertRaises(ValueError):
            apply_config_bin_dae_defaults(bytes(0xF0), bytes(config_bin))

        with self.assertRaises(ValueError):
            apply_config_bin_dae_defaults(bytes(0xEF), bytes(config_bin))

    def test_config_initializer_rejects_wrong_sized_band_items(self):
        items = {
            0x82: bytes(8),
            0x83: bytes(8),
            0x84: bytes(8),
            0x85: bytes(8),
            0x98: bytes(8),
            0xA5: bytes(8),
            0x86: bytes(11),
        }
        config_bin = bytearray(0x800)
        payload_offset = len(config_bin)
        for item_id, payload in sorted(items.items()):
            struct.pack_into(
                "<II",
                config_bin,
                0x10 + item_id * 8,
                payload_offset,
                len(payload),
            )
            config_bin.extend(payload)
            payload_offset += len(payload)

        with self.assertRaises(ValueError):
            apply_config_bin_dae_defaults(bytes(0xF0), bytes(config_bin))

    def test_zero_gain_custom_and_off_have_different_consumer_gates(self):
        empty_structure = bytes(0xF0)
        off = build_firmware_eq_structure(0, 1, empty_structure)
        custom = build_firmware_eq_structure(7, 1, empty_structure, (0,) * 7)

        off_gates = select_aud_dev_dae_groups(off.structure, 48_000)
        custom_gates = select_aud_dev_dae_groups(custom.structure, 48_000)

        self.assertTrue(off_gates.eq_bank_enabled)
        self.assertTrue(custom_gates.eq_bank_enabled)
        self.assertFalse(off_gates.output_gain_enabled)
        self.assertTrue(custom_gates.output_gain_enabled)
        for band_index in range(7):
            gain_offset = 0x10 + band_index * 8 + 4
            self.assertEqual(off.structure[gain_offset : gain_offset + 2], b"\0\0")
            self.assertEqual(custom.structure[gain_offset : gain_offset + 2], b"\0\0")

    def test_each_group_requires_its_flag_to_equal_one(self):
        configuration = bytearray(0xF0)
        configuration[0x07:0x0E] = bytes((1, 2, 1, 1, 0, 1, 1))

        selected = select_aud_dev_dae_groups(bytes(configuration), 48_000)

        self.assertTrue(selected.eq_bank_enabled)
        self.assertFalse(selected.speaker_compensation_enabled)
        self.assertTrue(selected.gain_group_one_enabled)
        self.assertTrue(selected.gain_group_two_enabled)
        self.assertFalse(selected.gain_group_three_enabled)
        self.assertTrue(selected.dynamic_range_enabled)
        self.assertTrue(selected.output_gain_enabled)

    def test_eq_bank_is_closed_above_48_khz(self):
        configuration = bytearray(0xF0)
        configuration[0x07] = 1

        at_limit = select_aud_dev_dae_groups(bytes(configuration), 48_000)
        above_limit = select_aud_dev_dae_groups(bytes(configuration), 48_001)

        self.assertTrue(at_limit.eq_bank_enabled)
        self.assertFalse(above_limit.eq_bank_enabled)

    def test_dae_gate_inputs_are_validated(self):
        with self.assertRaises(ValueError):
            select_aud_dev_dae_groups(bytes(0xEF), 48_000)

        with self.assertRaises(ValueError):
            select_aud_dev_dae_groups(bytes(0xF0), 0)


if __name__ == "__main__":
    unittest.main()
