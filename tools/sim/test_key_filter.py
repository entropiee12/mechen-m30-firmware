import unittest

from key_filter import (
    KEY_DOWN,
    KEY_LONG_PRESS,
    KEY_NEXT,
    KEY_PLAY_PAUSE,
    KEY_PREVIOUS,
    KEY_RELEASE,
    KEY_VOLUME_DOWN,
    KEY_VOLUME_UP,
    SCREEN_OFF,
    ScreenOffKeyPolicy,
    FilterDecision,
    KeyEvent,
    KeyFilterState,
    WAKE_MESSAGE,
)


class KeyFilterTests(unittest.TestCase):
    def test_screen_on_delivers_key_down_and_resets_idle_counters(self) -> None:
        event = KeyEvent(KEY_NEXT, KEY_DOWN)

        decision = KeyFilterState().process(event)

        self.assertEqual(
            decision,
            FilterDecision("deliver", event, None, True, None),
        )

    def test_stock_screen_off_event_becomes_wake_message(self) -> None:
        state = KeyFilterState(screen_state=SCREEN_OFF)

        decision = state.process(KeyEvent(0x07, KEY_DOWN))

        self.assertEqual(decision.action, "wake")
        self.assertEqual(decision.wake_message, WAKE_MESSAGE)
        self.assertEqual(decision.wake_key, 0x07)
        self.assertIsNone(decision.delivered)

    def test_stock_wake_key_long_press_and_release_are_discarded(self) -> None:
        state = KeyFilterState(screen_state=SCREEN_OFF)
        state.process(KeyEvent(0x07, KEY_DOWN))

        long_press = state.process(KeyEvent(0x07, KEY_LONG_PRESS))
        release = state.process(KeyEvent(0x07, KEY_RELEASE))

        self.assertEqual(long_press.action, "drop-wake-sequence")
        self.assertEqual(release.action, "drop-wake-sequence")
        self.assertIsNone(state.wake_key)

    def test_patched_screen_off_media_key_reaches_application(self) -> None:
        event = KeyEvent(KEY_NEXT, KEY_DOWN)
        state = KeyFilterState(screen_state=SCREEN_OFF)

        decision = state.process(event, patched=True)

        self.assertEqual(decision.action, "deliver")
        self.assertEqual(decision.delivered, event)
        self.assertTrue(decision.idle_counters_reset)
        self.assertIsNone(decision.wake_message)

    def test_media_keys_pass_policy_reaches_application_without_patch_alias(self) -> None:
        event = KeyEvent(KEY_NEXT, KEY_DOWN)
        state = KeyFilterState(
            screen_state=SCREEN_OFF,
            screen_off_policy=ScreenOffKeyPolicy.MEDIA_KEYS_PASS,
        )

        decision = state.process(event)

        self.assertEqual(decision.action, "deliver")
        self.assertEqual(decision.delivered, event)
        self.assertTrue(decision.idle_counters_reset)

    def test_wake_only_policy_keeps_media_key_as_wake_event(self) -> None:
        state = KeyFilterState(
            screen_state=SCREEN_OFF,
            screen_off_policy=ScreenOffKeyPolicy.WAKE_ONLY,
        )

        decision = state.process(KeyEvent(KEY_NEXT, KEY_DOWN))

        self.assertEqual(decision.action, "wake")
        self.assertEqual(decision.wake_message, WAKE_MESSAGE)

    def test_patched_screen_off_non_media_key_keeps_stock_wake_behavior(self) -> None:
        state = KeyFilterState(screen_state=SCREEN_OFF)

        decision = state.process(KeyEvent(0x07, KEY_DOWN), patched=True)

        self.assertEqual(decision.action, "wake")
        self.assertEqual(decision.wake_message, WAKE_MESSAGE)

    def test_every_patched_media_key_is_allowed(self) -> None:
        state = KeyFilterState(screen_state=SCREEN_OFF)

        for key_code in (
            KEY_PLAY_PAUSE,
            KEY_PREVIOUS,
            KEY_NEXT,
            KEY_VOLUME_UP,
            KEY_VOLUME_DOWN,
        ):
            decision = state.process(KeyEvent(key_code, KEY_DOWN), patched=True)
            self.assertEqual(decision.action, "deliver")

    def test_power_keeps_the_stock_power_path_while_screen_is_off(self) -> None:
        state = KeyFilterState(screen_state=SCREEN_OFF)

        decision = state.process(KeyEvent(0x81, KEY_DOWN))

        self.assertEqual(decision.action, "deliver")
        self.assertTrue(decision.idle_counters_reset)


if __name__ == "__main__":
    unittest.main()
