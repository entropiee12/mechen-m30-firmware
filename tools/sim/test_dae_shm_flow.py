import hashlib
import struct
import unittest
from pathlib import Path

from dae_shm_flow import (
    SHM_BASE_POINTER,
    SHM_ID,
    DaeSharedMemoryFlow,
    FirmwareEqAudioSession,
    mmm_mp_pp_dsp_name,
    mmm_pp_pp_dsp_name,
)
from dsp_model import DAE_CONFIG_BYTES
from m30_model import (
    FIRMWARE_EQ_SLIDER_DECREASE_EVENT,
    FIRMWARE_EQ_SLIDER_INCREASE_EVENT,
)


def dae_with_marker() -> bytes:
    data = bytearray(DAE_CONFIG_BYTES)
    data[0x20] = 0xAB
    return bytes(data)


def local_with(**offsets: int) -> bytes:
    data = bytearray(DAE_CONFIG_BYTES)
    for name, value in offsets.items():
        data[int(name.removeprefix("o"), 16)] = value

    return bytes(data)


class DspNameTests(unittest.TestCase):
    def shm(self, plus_two):
        data = bytearray(DAE_CONFIG_BYTES)
        data[2] = plus_two
        return bytes(data)

    def test_mmm_mp_rule_zero_is_adppext_at_every_rate(self):
        for rate in (44100, 48000, 96000):
            with self.subTest(rate=rate):
                self.assertEqual(
                    mmm_mp_pp_dsp_name(self.shm(0), rate), "adppext.dsp"
                )

    def test_mmm_mp_rule_nonzero_splits_at_48_khz(self):
        for plus_two in (1, 0xFF):
            self.assertEqual(
                mmm_mp_pp_dsp_name(self.shm(plus_two), 44100), "adppdrc.dsp"
            )
            self.assertEqual(
                mmm_mp_pp_dsp_name(self.shm(plus_two), 48000), "adppdrc.dsp"
            )
            self.assertEqual(
                mmm_mp_pp_dsp_name(self.shm(plus_two), 48001), "audiopp.dsp"
            )
            self.assertEqual(
                mmm_mp_pp_dsp_name(self.shm(plus_two), 96000), "audiopp.dsp"
            )

    def test_mmm_pp_variant_has_no_rate_split(self):
        self.assertEqual(mmm_pp_pp_dsp_name(self.shm(0)), "adppext.dsp")
        self.assertEqual(mmm_pp_pp_dsp_name(self.shm(1)), "audiopp.dsp")

    def test_rejects_bad_inputs(self):
        with self.assertRaises(ValueError):
            mmm_mp_pp_dsp_name(bytes(0x10), 44100)

        with self.assertRaises(ValueError):
            mmm_mp_pp_dsp_name(self.shm(0), 0)


class ConfigBootTests(unittest.TestCase):
    def test_state_flag_one_writes_plus_two_and_registers_id_three(self):
        flow = DaeSharedMemoryFlow(
            state_flag=1,
            dae_configuration=dae_with_marker(),
        )
        self.assertEqual(flow.shared_memory[2:4], bytes([1, 0]))
        self.assertEqual(flow.shared_memory[0x20], 0xAB)
        self.assertEqual(flow.registry.attach(SHM_ID), SHM_BASE_POINTER)

    def test_other_state_flags_write_plus_three(self):
        for state_flag in (0, 2):
            with self.subTest(state_flag=state_flag):
                flow = DaeSharedMemoryFlow(
                    state_flag=state_flag,
                    dae_configuration=dae_with_marker(),
                        )
                self.assertEqual(flow.shared_memory[2:4], bytes([0, 1]))

    def test_rejects_wrong_sized_structures(self):
        with self.assertRaises(ValueError):
            DaeSharedMemoryFlow(
                state_flag=1,
                dae_configuration=bytes(0x10),
                )

        with self.assertRaises(ValueError):
            DaeSharedMemoryFlow(
                state_flag=1,
                dae_configuration=bytes(DAE_CONFIG_BYTES),
                    mengine_local_structure=bytes(0x10),
            )


class BuilderGateTests(unittest.TestCase):
    def make(self, *, active=None, state_flag=1, local=None):
        return DaeSharedMemoryFlow(
            state_flag=state_flag,
            dae_configuration=bytes(DAE_CONFIG_BYTES),
            engine_active=active,
            mengine_local_structure=local,
        )

    def test_gate_follows_the_persisted_plus_0x18_byte_by_default(self):
        self.assertTrue(self.make(state_flag=1).engine_active)
        self.assertTrue(self.make(state_flag=2).engine_active)
        self.assertFalse(self.make(state_flag=0).engine_active)

    def test_closed_gate_never_copies(self):
        flow = self.make(state_flag=0)
        before = flow.shared_memory
        self.assertFalse(flow.eq_message(7, 1))
        self.assertEqual(flow.shared_memory, before)
        self.assertEqual(flow.play(44100), "adppext.dsp")

    def test_open_gate_copies_even_for_off(self):
        flow = self.make()
        self.assertTrue(flow.eq_message(0, 0))
        self.assertEqual(flow.shared_memory[7], 1)
        self.assertEqual(flow.shared_memory[0x0D], 0)

    def test_preset_sets_the_output_gain_flag(self):
        flow = self.make()
        self.assertTrue(flow.eq_message(7, 1))
        self.assertEqual(flow.shared_memory[0x0D], 1)

    def test_copy_keeps_config_plus_two_because_mengine_read_it_back(self):
        flow = self.make(state_flag=1)
        self.assertTrue(flow.eq_message(7, 2))
        self.assertEqual(flow.shared_memory[2:4], bytes([1, 0]))

    def test_a_rewritten_flash_block_can_clear_plus_two(self):
        flow = self.make(state_flag=1, local=local_with(o02=0, o03=1))
        self.assertTrue(flow.eq_message(7, 2))
        self.assertEqual(flow.shared_memory[2:4], bytes([0, 1]))
        self.assertEqual(flow.play(44100), "adppext.dsp")

    def test_dynamic_range_mode_blocks_the_builder(self):
        flow = self.make(local=local_with(o02=1, o0c=1))
        before = flow.shared_memory
        self.assertFalse(flow.eq_message(7, 1))
        self.assertEqual(flow.shared_memory, before)

    def test_shared_pointer_is_the_one_config_registered(self):
        flow = self.make()
        pointer = flow.shared_pointer
        flow.eq_message(7, 3)
        self.assertEqual(flow.shared_pointer, pointer)
        self.assertEqual(pointer, SHM_BASE_POINTER)

    def test_custom_requires_explicit_gains(self):
        flow = self.make()
        with self.assertRaises(ValueError):
            flow.eq_message(7, 7)

        self.assertTrue(flow.eq_message(7, 7, custom=(0, 0, 0, 0, 0, 0, 0)))


class PlayTests(unittest.TestCase):
    def test_default_config_opens_adppdrc_below_and_audiopp_above_48_khz(self):
        flow = DaeSharedMemoryFlow(
            state_flag=1,
            dae_configuration=bytes(DAE_CONFIG_BYTES),
        )
        self.assertEqual(flow.play(44100), "adppdrc.dsp")
        self.assertEqual(flow.play(48000), "adppdrc.dsp")
        self.assertEqual(flow.play(96000), "audiopp.dsp")
        self.assertEqual(flow.shared_memory[2], 1)

    def test_state_flag_zero_opens_adppext(self):
        flow = DaeSharedMemoryFlow(
            state_flag=0,
            dae_configuration=bytes(DAE_CONFIG_BYTES),
        )
        self.assertEqual(flow.play(44100), "adppext.dsp")
        self.assertEqual(flow.play(96000), "adppext.dsp")

    def test_state_flag_two_opens_the_gate_but_still_selects_adppext(self):
        # Matches run_config_to_mengine_dae_chain_probe.sh: item 0x96 = 2.
        flow = DaeSharedMemoryFlow(
            state_flag=2,
            dae_configuration=bytes(DAE_CONFIG_BYTES),
        )
        self.assertTrue(flow.engine_active)
        self.assertEqual(flow.play(44100), "adppext.dsp")
        self.assertEqual(flow.play(96000), "adppext.dsp")
        self.assertEqual(flow.shared_memory[2:4], bytes([0, 1]))
        self.assertEqual(flow.shared_memory[7], 1)

    def test_play_runs_the_builder_before_the_dsp_choice(self):
        flow = DaeSharedMemoryFlow(
            state_flag=1,
            dae_configuration=bytes(DAE_CONFIG_BYTES),
        )
        self.assertEqual(flow.shared_memory[7], 0)
        flow.play(44100)
        self.assertEqual(flow.shared_memory[7], 1)


class MusicStartTests(unittest.TestCase):
    def make(self):
        return DaeSharedMemoryFlow(
            state_flag=1,
            dae_configuration=bytes(DAE_CONFIG_BYTES),
        )

    def test_start_sends_the_stored_selector(self):
        flow = self.make()
        self.assertTrue(flow.music_start(2, engine_playing=False))
        self.assertEqual(flow.selector_byte, 2)
        self.assertEqual(flow.eq_type_byte, 7)

    def test_running_engine_sends_nothing(self):
        flow = self.make()
        self.assertFalse(flow.music_start(2, engine_playing=True))
        self.assertEqual(flow.selector_byte, 0)

    def test_unknown_selector_is_treated_as_off(self):
        flow = self.make()
        flow.music_start(9, engine_playing=False)
        self.assertEqual((flow.eq_type_byte, flow.selector_byte), (0, 0))


class FirmwareEqAudioSessionTests(unittest.TestCase):
    def make(self, *, state_flag=1, active=None):
        return FirmwareEqAudioSession(
            state_flag=state_flag,
            dae_configuration=bytes(DAE_CONFIG_BYTES),
            custom_gains=(0, 0, 0, 0, 0, 0, 0),
            engine_active=active,
        )

    def test_custom_edit_submits_builder_output_and_play_selects_dsp(self):
        session = self.make()

        self.assertTrue(session.edit_custom(0, FIRMWARE_EQ_SLIDER_INCREASE_EVENT))
        self.assertEqual(session.flow.shared_memory[7], 1)
        self.assertEqual(
            struct.unpack_from("<h", session.flow.shared_memory, 0x14)[0],
            10,
        )
        self.assertEqual(session.play(48000), "adppdrc.dsp")
        self.assertEqual(session.play(48001), "audiopp.dsp")
        self.assertEqual(session.last_auddev_state.eq_flag_3a, 0)
        self.assertEqual(session.last_auddev_state.busy_28, 0)

    def test_play_exposes_bounded_auddev_state_at_48_khz(self):
        session = self.make()
        session.music_start(1, engine_playing=False)

        self.assertEqual(session.play(48000), "adppdrc.dsp")
        self.assertEqual(session.last_auddev_state.eq_flag_3a, 1)
        self.assertEqual(session.last_auddev_state.busy_28, 1)
        self.assertEqual(session.last_auddev_state.drc_flag_40, 0)
        self.assertEqual(
            session.last_auddev_state.bands,
            session.flow.shared_memory[0x10:0x90],
        )

    def test_cancel_restores_slider_but_not_the_last_previewed_engine_curve(self):
        session = self.make()
        session.edit_custom(0, FIRMWARE_EQ_SLIDER_DECREASE_EVENT)
        previewed = session.flow.shared_memory

        session.cancel_custom()

        self.assertEqual(session.slider.custom_gains, (0, 0, 0, 0, 0, 0, 0))
        self.assertEqual(session.flow.shared_memory, previewed)
        self.assertEqual(struct.unpack_from("<h", previewed, 0x14)[0], -10)

    def test_inactive_override_keeps_slider_edit_out_of_shared_memory(self):
        session = self.make(active=False)

        self.assertTrue(session.edit_custom(0, FIRMWARE_EQ_SLIDER_INCREASE_EVENT))
        self.assertEqual(session.flow.shared_memory[7], 0)
        self.assertEqual(session.play(44100), "adppdrc.dsp")

    def test_trace_separates_host_model_from_unexecuted_services(self):
        session = self.make()
        session.music_start(2, engine_playing=False)
        session.play(44100)

        trace = session.trace()

        self.assertEqual(trace["selector"], 2)
        self.assertEqual(trace["last_dsp_name"], "adppdrc.dsp")
        self.assertEqual(trace["auddev"]["eq_flag_3a"], 1)
        self.assertIn("ROM services", trace["provenance"])


MENGINE_PATH = (
    Path(__file__).resolve().parents[2] / "analysis" / "community" / "fw" / "mengine.ap"
)
MENGINE_SHA256 = "b569605e9f66ef94023cf82d665ae119f6bbb7ed0c9d8f5e6c5be867ce91be49"
MENGINE_LOCAL_STRUCTURE = 0x9FC15DD4


CONFIG_PATH = (
    Path(__file__).resolve().parents[2] / "analysis" / "community" / "fw" / "config.ap"
)
CONFIG_SHA256 = "5b5687c00880e691500c7abbfba43dab51ae0f95d2203f74aced2e8d56357580"


def pool_words(path, expected_sha256, offsets):
    if not path.is_file():
        raise unittest.SkipTest(f"{path.name} is not available")

    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != expected_sha256:
        raise unittest.SkipTest(f"{path.name} has an unexpected SHA-256")

    return [struct.unpack_from("<I", data, offset)[0] for offset in offsets]


class PersistedBlockChainTests(unittest.TestCase):
    def test_config_saves_the_settings_and_dae_blocks(self):
        # FUN_40054f38 pool: settings RAM, helper stub, DAE RAM, DAE flash id.
        words = pool_words(CONFIG_PATH, CONFIG_SHA256, (0x176C, 0x1774, 0x1778))
        self.assertEqual(words, [0x9FC15654, 0x9FC15700, 0x000F0000])

    def test_mengine_reads_the_same_ids_into_its_local_copies(self):
        # FUN_60050d00 pool at 0x60050ea4 (file 0x11a4): settings RAM, ..., DAE RAM, id.
        words = pool_words(
            MENGINE_PATH,
            MENGINE_SHA256,
            (0x11A4, 0x11AC, 0x11B0),
        )
        self.assertEqual(words, [0x9FC15D9C, 0x9FC15DD4, 0x000F0000])


if __name__ == "__main__":
    unittest.main()
