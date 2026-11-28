#!/usr/bin/env python3
import unittest

from m30_model import (
    ArtworkSession,
    CardChangePolicy,
    DEFAULT_VOLUME_LIMIT,
    EQ_PRESETS,
    FIRMWARE_EQ_2024_04_PROFILE,
    FIRMWARE_EQ_BAND_HZ,
    FIRMWARE_EQ_CURRENT_PROFILE,
    FIRMWARE_EQ_SETTING_CUSTOM_ROW,
    FIRMWARE_EQ_ZEROED_BLOCK_CUSTOM,
    FIRMWARE_EQ_MESSAGE_ID,
    FIRMWARE_EQ_PRESETS,
    FIRMWARE_EQ_Q_VALUES,
    FIRMWARE_EQ_SELECTOR_OFFSET,
    FIRMWARE_EQ_SLIDER_INCREASE_EVENT,
    ApplicationLaunchRequest,
    IndexState,
    IndexBootHandoff,
    IndexStatus,
    PlaylistUpdateGateResult,
    MAX_VOLUME_LEVEL,
    SleepTimerPersistence,
    SleepTimerState,
    VolumeLimitState,
    FirmwareEqSliderSession,
    adjust_firmware_eq_slider_gain,
    eq_names,
    find_eq,
    firmware_eq_gains,
    firmware_eq_gain_s16,
    build_firmware_eq_structure,
    firmware_eq_command,
    firmware_eq_payload,
    find_firmware_eq_profile,
    playlist_update_gate,
    route_music_app_return_code,
    shared_message_return_code,
    sort_filenames,
)


class FilenameOrderTests(unittest.TestCase):
    def test_numeric_runs_are_natural(self):
        names = ["Track 10.flac", "Track 2.flac", "Track 1.flac"]
        self.assertEqual(sort_filenames(names), ["Track 1.flac", "Track 2.flac", "Track 10.flac"])

    def test_extension_is_not_the_primary_sort_field(self):
        names = ["02 - Song.mp3", "01 - Song.flac", "10 - Song.ogg"]
        self.assertEqual(sort_filenames(names), ["01 - Song.flac", "02 - Song.mp3", "10 - Song.ogg"])

    def test_case_and_path_fallback_are_deterministic(self):
        names = ["/Music/zeta.MP3", "/Music/Alpha.mp3", "/Music/alpha.flac"]
        self.assertEqual(sort_filenames(names), ["/Music/alpha.flac", "/Music/Alpha.mp3", "/Music/zeta.MP3"])


class IndexStatusTests(unittest.TestCase):
    def test_manual_policy_never_starts_an_update_implicitly(self):
        status = IndexStatus()
        status.powered_card_reinserted()
        self.assertEqual(status.state, IndexState.DIRTY)
        self.assertEqual(status.progress, 0)
        self.assertIn("Manual", status.message)

    def test_observed_powered_reinsertion_starts_an_update(self):
        status = IndexStatus(card_change_policy=CardChangePolicy.AUTOMATIC_REBUILD)
        status.powered_card_reinserted(files_total=4)
        self.assertEqual(status.state, IndexState.UPDATING)
        self.assertEqual(status.files_total, 4)
        self.assertEqual(status.progress, 0)
        self.assertIn("powered card reinsertion", status.message)

    def test_automatic_powered_reinsertion_requires_a_file_count(self):
        status = IndexStatus(card_change_policy=CardChangePolicy.AUTOMATIC_REBUILD)
        with self.assertRaises(ValueError):
            status.powered_card_reinserted()

    def test_update_progress_and_completion(self):
        status = IndexStatus()
        status.begin(4)
        status.advance(1)
        status.advance(3)
        self.assertEqual(status.progress, 100)
        status.complete()
        self.assertEqual(status.state, IndexState.UP_TO_DATE)

    def test_incomplete_update_cannot_complete(self):
        status = IndexStatus()
        status.begin(2)
        status.advance(1)
        with self.assertRaises(RuntimeError):
            status.complete()


class IndexBootHandoffTests(unittest.TestCase):
    def test_invalid_persistent_settings_block_sets_flag_and_launches_playlist(self):
        handoff = IndexBootHandoff()

        request = handoff.config_start(settings_block_valid=False)

        self.assertEqual(handoff.flag_word, 0x10000)
        self.assertEqual(request, ApplicationLaunchRequest(0x201, 10, 0x4E))

    def test_existing_flag_launches_playlist_until_successful_update_clears_it(self):
        handoff = IndexBootHandoff(flag_word=0x10000)

        request = handoff.config_start(settings_block_valid=True)
        self.assertEqual(request, ApplicationLaunchRequest(0x201, 10, 0x4E))

        handoff.playlist_update_succeeded()

        self.assertEqual(handoff.flag_word, 0)
        self.assertIsNone(handoff.config_start(settings_block_valid=True))

    def test_invalid_block_overwrites_old_flag_word_with_index_flag(self):
        handoff = IndexBootHandoff(flag_word=0x20)

        handoff.config_start(settings_block_valid=False)

        self.assertEqual(handoff.flag_word, 0x10000)

    def test_config_start_requires_a_boolean_validity_result(self):
        handoff = IndexBootHandoff()

        with self.assertRaises(ValueError):
            handoff.config_start(settings_block_valid=1)


class IndexReturnPathTests(unittest.TestCase):
    def test_playlist_gate_uses_parameter_bit_0x40(self):
        for parameter in (0x3F, 0x80, 0x82):
            with self.subTest(parameter=parameter):
                self.assertEqual(
                    playlist_update_gate(parameter, 0),
                    PlaylistUpdateGateResult(False, ()),
                )

        for parameter in (0x40, 0x41, 0x42, 0x4E, 0x7F, 0xC2):
            with self.subTest(parameter=parameter):
                self.assertEqual(
                    playlist_update_gate(parameter, 0),
                    PlaylistUpdateGateResult(True, ("MUSIC.LIB", "M3U.LIB")),
                )

    def test_playlist_gate_rejects_only_the_all_ones_resident_result(self):
        self.assertEqual(
            playlist_update_gate(0x42, -1),
            PlaylistUpdateGateResult(True, ()),
        )
        self.assertEqual(
            playlist_update_gate(0x42, 0xFFFFFFFF),
            PlaylistUpdateGateResult(True, ()),
        )

        for resident_result in (-(1 << 31), -2, 0, 1, (1 << 31) - 1):
            with self.subTest(resident_result=resident_result):
                self.assertEqual(
                    playlist_update_gate(0x42, resident_result),
                    PlaylistUpdateGateResult(True, ("MUSIC.LIB", "M3U.LIB")),
                )

    def test_playlist_gate_rejects_parameters_outside_the_app_parameter_byte(self):
        for parameter in (-1, 0x100):
            with self.subTest(parameter=parameter):
                with self.assertRaises(ValueError):
                    playlist_update_gate(parameter, 0)

    def test_shared_message_b0_returns_the_observed_index_handoff_code(self):
        self.assertEqual(shared_message_return_code(0xB0), 0x861C)
        self.assertIsNone(shared_message_return_code(0xB1))

    def test_music_return_code_launches_playlist_and_clears_cleanup_record(self):
        for parameter in (0x42, 0x82, 0xC2):
            with self.subTest(parameter=parameter):
                cleanup_record = bytearray([0xA5] * 0xA8)
                return_code = shared_message_return_code(0xB0)

                request = route_music_app_return_code(
                    return_code,
                    parameter,
                    cleanup_record,
                )

                self.assertEqual(request, ApplicationLaunchRequest(0x201, 10, parameter))
                self.assertEqual(cleanup_record, bytes(0xA8))

    def test_other_application_return_codes_leave_request_and_cleanup_untouched(self):
        cleanup_record = bytearray([0xA5] * 0xA8)

        request = route_music_app_return_code(0x8006, 0x42, cleanup_record)

        self.assertIsNone(request)
        self.assertEqual(cleanup_record, bytes([0xA5] * 0xA8))

    def test_music_index_handoff_rejects_unobserved_parameter_or_record_size(self):
        cleanup_record = bytearray(0xA8)

        with self.assertRaises(ValueError):
            route_music_app_return_code(0x861C, 0x4E, cleanup_record)

        with self.assertRaises(ValueError):
            route_music_app_return_code(0x861C, 0x42, bytearray(0xA7))


class VolumeLimitTests(unittest.TestCase):
    def test_documented_default_limit_is_full_volume_range(self):
        state = VolumeLimitState()

        self.assertEqual(DEFAULT_VOLUME_LIMIT, MAX_VOLUME_LEVEL)
        self.assertEqual(state.limit, 100)
        self.assertEqual(state.clamp(100), 100)

    def test_configured_limit_clamps_requested_volume(self):
        state = VolumeLimitState()

        state.set_limit(20)

        self.assertEqual(state.clamp(0), 0)
        self.assertEqual(state.clamp(20), 20)
        self.assertEqual(state.clamp(100), 20)

    def test_volume_limit_rejects_levels_outside_the_observed_range(self):
        with self.assertRaises(ValueError):
            VolumeLimitState(-1)
        with self.assertRaises(ValueError):
            VolumeLimitState(MAX_VOLUME_LEVEL + 1)

        state = VolumeLimitState()
        with self.assertRaises(ValueError):
            state.set_limit(MAX_VOLUME_LEVEL + 1)
        with self.assertRaises(ValueError):
            state.clamp(-1)


class SleepTimerTests(unittest.TestCase):
    def test_persistent_selection_survives_reboot(self):
        state = SleepTimerState()
        opaque_value = 30
        state.select(opaque_value)

        state.reboot()

        self.assertEqual(state.selected_value, opaque_value)

    def test_one_shot_selection_is_cleared_on_reboot(self):
        state = SleepTimerState(persistence=SleepTimerPersistence.ONE_SHOT)
        state.select(30)

        state.reboot()

        self.assertIsNone(state.selected_value)

    def test_reboot_does_not_claim_to_advance_elapsed_time(self):
        state = SleepTimerState(selected_value=30)

        state.reboot()

        self.assertEqual(state.selected_value, 30)


class EqCatalogTests(unittest.TestCase):
    def test_catalog_has_requested_named_presets(self):
        self.assertIn("Funk", eq_names())
        self.assertIn("Hip-Hop", eq_names())
        self.assertGreaterEqual(len(EQ_PRESETS), 12)

    def test_lookup_is_case_insensitive(self):
        self.assertEqual(find_eq("rOcK").bands_db, (3, 2, -1, 2, 3))


class FirmwareEqContractTests(unittest.TestCase):
    def test_recovered_geometry_is_seven_band(self):
        self.assertEqual(FIRMWARE_EQ_BAND_HZ, (80, 200, 480, 980, 4000, 8000, 16000))
        self.assertEqual(FIRMWARE_EQ_Q_VALUES, (10, 6, 4, 6, 10, 5, 8))
        self.assertEqual(len(FIRMWARE_EQ_PRESETS), 8)

    def test_revision_profile_preserves_verified_2024_frequency_exception(self):
        self.assertEqual(FIRMWARE_EQ_CURRENT_PROFILE.band_hz[0], 80)
        self.assertEqual(FIRMWARE_EQ_2024_04_PROFILE.band_hz[0], 50)
        self.assertEqual(FIRMWARE_EQ_CURRENT_PROFILE.q_values, FIRMWARE_EQ_2024_04_PROFILE.q_values)
        self.assertEqual(FIRMWARE_EQ_CURRENT_PROFILE.presets, FIRMWARE_EQ_2024_04_PROFILE.presets)

    def test_revision_profile_lookup_is_case_insensitive(self):
        self.assertIs(
            find_firmware_eq_profile("2024-04"),
            FIRMWARE_EQ_2024_04_PROFILE,
        )
        with self.assertRaises(KeyError):
            find_firmware_eq_profile("unknown")

    def test_payload_matches_music_ap_selector_protocol(self):
        self.assertEqual(firmware_eq_payload("Off"), (0, 0))
        self.assertEqual(firmware_eq_payload("Rock"), (7, 1))
        self.assertEqual(firmware_eq_payload("Techno"), (7, 6))
        self.assertEqual(firmware_eq_payload("Custom"), (7, 7))

    def test_command_matches_verified_engine_message_boundary(self):
        command = firmware_eq_command("Custom")
        self.assertEqual(command.message_id, FIRMWARE_EQ_MESSAGE_ID)
        self.assertEqual((command.eq_type, command.selector), (7, 7))
        self.assertEqual(command.selector_source_offset, FIRMWARE_EQ_SELECTOR_OFFSET)

    def test_recovered_gain_rows_match_mengine(self):
        self.assertEqual(firmware_eq_gains(0), (0, 0, 0, 0, 0, 0, 0))
        self.assertEqual(firmware_eq_gains(1), (5, 3, 0, -1, 0, 4, 5))
        self.assertEqual(firmware_eq_gains(6), (5, 2, 0, 0, 0, 0, 6))

    def test_custom_slider_steps_follow_the_signed_mips16_boundaries(self):
        expected_steps = (
            (-3, 0x2903, -2),
            (-3, 0x2904, -3),
            (-2, 0x2903, -1),
            (-2, 0x2904, -3),
            (-1, 0x2903, 0),
            (-1, 0x2904, -2),
            (0, 0x2903, 1),
            (0, 0x2904, -1),
            (1, 0x2903, 2),
            (1, 0x2904, 0),
            (2, 0x2903, 3),
            (2, 0x2904, 1),
            (3, 0x2903, 3),
            (3, 0x2904, 2),
        )

        for current_gain, event, expected_gain in expected_steps:
            with self.subTest(current_gain=current_gain, event=event):
                self.assertEqual(
                    adjust_firmware_eq_slider_gain(current_gain, event),
                    expected_gain,
                )

    def test_custom_slider_rejects_unobserved_gain_or_event(self):
        with self.assertRaises(ValueError):
            adjust_firmware_eq_slider_gain(4, 0x2903)

        with self.assertRaises(ValueError):
            adjust_firmware_eq_slider_gain(0, 0x2905)

    def test_custom_slider_cancel_restores_saved_values_without_rollback_message(self):
        session = FirmwareEqSliderSession((0, 0, 0, 0, 0, 0, 0))

        self.assertTrue(session.edit(0, FIRMWARE_EQ_SLIDER_INCREASE_EVENT))
        session.cancel()

        self.assertEqual(session.custom_gains, (0, 0, 0, 0, 0, 0, 0))
        self.assertEqual(session.last_submitted_gains, (1, 0, 0, 0, 0, 0, 0))
        self.assertEqual(session.producer_updates, 1)
        self.assertEqual(session.selector, 7)

    def test_custom_slider_confirm_keeps_previewed_values_without_extra_message(self):
        session = FirmwareEqSliderSession((0, 0, 0, 0, 0, 0, 0))

        self.assertTrue(session.edit(0, FIRMWARE_EQ_SLIDER_INCREASE_EVENT))
        session.confirm()

        self.assertEqual(session.custom_gains, (1, 0, 0, 0, 0, 0, 0))
        self.assertEqual(session.persisted_gains, (0, 0, 0, 0, 0, 0, 0))
        self.assertEqual(session.last_submitted_gains, session.custom_gains)
        self.assertEqual(session.producer_updates, 1)
        self.assertEqual(session.selector, 7)

    def test_custom_slider_gains_persist_only_when_mainmenu_exits(self):
        session = FirmwareEqSliderSession((0, 0, 0, 0, 0, 0, 0))

        session.edit(0, FIRMWARE_EQ_SLIDER_INCREASE_EVENT)
        session.confirm()
        self.assertEqual(session.persisted_gains, (0, 0, 0, 0, 0, 0, 0))

        session.persist_on_mainmenu_exit()

        self.assertEqual(session.persisted_gains, (1, 0, 0, 0, 0, 0, 0))

    def test_custom_slider_boundary_does_not_submit_unchanged_values(self):
        session = FirmwareEqSliderSession((3, 0, 0, 0, 0, 0, 0))

        self.assertFalse(session.edit(0, FIRMWARE_EQ_SLIDER_INCREASE_EVENT))

        self.assertEqual(session.custom_gains, (3, 0, 0, 0, 0, 0, 0))
        self.assertIsNone(session.last_submitted_gains)
        self.assertEqual(session.producer_updates, 0)

    def test_custom_slider_session_rejects_wrong_shape_or_gain_range(self):
        with self.assertRaises(ValueError):
            FirmwareEqSliderSession((0, 0, 0))

        with self.assertRaises(ValueError):
            FirmwareEqSliderSession((0, 0, 0, 0, 0, 0, 4))

        session = FirmwareEqSliderSession((0, 0, 0, 0, 0, 0, 0))
        with self.assertRaises(IndexError):
            session.edit(7, FIRMWARE_EQ_SLIDER_INCREASE_EVENT)

    def test_builder_scales_signed_gain_bytes_into_signed_halfwords(self):
        custom = (1, 2, 3, -3, -2, 127, -128)

        self.assertEqual(
            firmware_eq_gain_s16(7, custom),
            (10, 20, 30, -30, -20, 1270, -1280),
        )

    def test_builder_matches_verified_structure_writes_and_preserves_unknown_bytes(self):
        initial = bytearray([0xAA] * 0xF0)
        initial[0x0C] = 0

        result = build_firmware_eq_structure(1, 1, bytes(initial))

        self.assertTrue(result.built)
        self.assertEqual(result.selector_copy, 1)
        self.assertEqual(result.structure[0x07], 1)
        self.assertEqual(result.structure[0x0C], 0)
        self.assertEqual(result.structure[0x0D], 1)
        self.assertEqual(result.structure[0x08:0x0C], bytes([0xAA] * 4))
        self.assertEqual(result.structure[0x0E:0x10], bytes([0xAA] * 2))
        self.assertEqual(result.structure[0x47], 0)
        self.assertEqual(result.structure[0x48:0x90], bytes(0x48))
        self.assertEqual(result.structure[0x90:], bytes([0xAA] * 0x60))

        first_band = (
            bytes.fromhex("50000a003200")
            + bytes((1, 0))
        )
        self.assertEqual(result.structure[0x10:0x18], first_band)

    def test_builder_selector_zero_sets_inactive_header_and_zero_gains(self):
        initial = bytearray([0xAA] * 0xF0)
        initial[0x0C] = 0

        result = build_firmware_eq_structure(0, 1, bytes(initial))

        self.assertTrue(result.built)
        self.assertEqual(result.structure[0x0D], 0)
        self.assertEqual(result.structure[0x10 + 4:0x10 + 6], bytes(2))

    def test_builder_gates_leave_structure_unchanged_after_selector_mirror(self):
        busy_structure = bytearray([0xAA] * 0xF0)
        busy_structure[0x0C] = 1
        inactive_structure = bytearray([0xAA] * 0xF0)
        inactive_structure[0x0C] = 0

        busy = build_firmware_eq_structure(3, 1, bytes(busy_structure))
        inactive = build_firmware_eq_structure(3, 0, bytes(inactive_structure))

        self.assertFalse(busy.built)
        self.assertEqual(busy.selector_copy, 3)
        self.assertEqual(busy.structure, bytes(busy_structure))
        self.assertFalse(inactive.built)
        self.assertEqual(inactive.selector_copy, 3)
        self.assertEqual(inactive.structure, bytes(inactive_structure))

    def test_custom_requires_exactly_seven_signed_int8_values(self):
        custom = FIRMWARE_EQ_ZEROED_BLOCK_CUSTOM

        self.assertEqual(custom, (0, 0, 0, 0, 0, 0, 0))
        self.assertEqual(
            FIRMWARE_EQ_CURRENT_PROFILE.setting_custom_row,
            FIRMWARE_EQ_SETTING_CUSTOM_ROW,
        )
        self.assertNotEqual(custom, FIRMWARE_EQ_SETTING_CUSTOM_ROW)
        self.assertIsNone(FIRMWARE_EQ_2024_04_PROFILE.setting_custom_row)
        self.assertEqual(firmware_eq_gains(7, custom), custom)
        self.assertEqual(
            firmware_eq_gains(
                7,
                custom,
                profile=FIRMWARE_EQ_2024_04_PROFILE,
            ),
            custom,
        )
        with self.assertRaises(ValueError):
            firmware_eq_gains(7, (0, 0, 0))
        with self.assertRaises(ValueError):
            firmware_eq_gains(7, (0, 0, 0, 0, 0, 0, 128))
        with self.assertRaises(ValueError):
            firmware_eq_gains(8)


class ArtworkLatencyTests(unittest.TestCase):
    def test_audio_starts_before_artwork_decode(self):
        session = ArtworkSession()
        session.start_track(has_artwork=True)
        self.assertEqual(session.events, ["audio-start", "artwork-placeholder"])
        session.decode_chunk()
        self.assertTrue(session.ui_blocked)
        session.finish_artwork()
        self.assertEqual(session.artwork_state, "ready")
        self.assertFalse(session.ui_blocked)
        self.assertEqual(session.events[0], "audio-start")

    def test_missing_artwork_does_not_block_playback(self):
        session = ArtworkSession()
        session.start_track(has_artwork=False)
        self.assertTrue(session.audio_started)
        self.assertEqual(session.artwork_state, "none")

    def test_decode_cannot_run_before_audio(self):
        session = ArtworkSession(artwork_state="pending")
        with self.assertRaises(RuntimeError):
            session.decode_chunk()


if __name__ == "__main__":
    unittest.main()
