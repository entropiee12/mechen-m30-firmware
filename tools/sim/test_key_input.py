import unittest

from key_filter import (
    KEY_DOWN,
    KEY_HOLD,
    KEY_LONG_PRESS,
    KEY_NEXT,
    KEY_RELEASE,
    SCREEN_OFF,
    WAKE_MESSAGE,
    ScreenOffKeyPolicy,
)
from key_input import KeyInputSession


class KeyInputSessionTests(unittest.TestCase):
    def test_screen_on_sample_reaches_the_application_filter(self) -> None:
        session = KeyInputSession((KEY_NEXT, 2, 3, 4, 5, 6, 7), override_value=8)

        trace = session.process_sample(0)

        self.assertEqual(trace.classification.normalized_value, KEY_NEXT)
        self.assertEqual((trace.driver_event.key_code, trace.driver_event.event_type), (KEY_NEXT, KEY_DOWN))
        self.assertEqual(trace.filter_decision.action, "deliver")
        self.assertEqual(trace.filter_decision.delivered, trace.driver_event)

    def test_repeat_samples_only_reach_the_filter_when_the_driver_emits(self) -> None:
        session = KeyInputSession((KEY_NEXT, 2, 3, 4, 5, 6, 7), override_value=8)
        session.process_sample(0)

        repeated = session.process_sample(0)
        self.assertIsNone(repeated.driver_event)
        self.assertIsNone(repeated.filter_decision)

        long_press = None
        for _ in range(3):
            long_press = session.process_sample(0)

        self.assertEqual(long_press.driver_event.event_type, KEY_LONG_PRESS)
        self.assertEqual(long_press.filter_decision.delivered, long_press.driver_event)

        hold = session.process_sample(0)
        self.assertEqual(hold.driver_event.event_type, KEY_HOLD)
        self.assertEqual(hold.filter_decision.delivered, hold.driver_event)

    def test_screen_off_wake_key_discards_repeats_until_release(self) -> None:
        session = KeyInputSession(
            (0x07, 0x30, 2, 3, 4, 5, 6),
            override_value=8,
            screen_state=SCREEN_OFF,
        )

        wake = session.process_sample(0)
        self.assertEqual(wake.filter_decision.action, "wake")
        self.assertEqual(wake.filter_decision.wake_message, WAKE_MESSAGE)

        session.process_sample(0)
        session.process_sample(0)
        session.process_sample(0)
        long_press = session.process_sample(0)
        self.assertEqual(long_press.driver_event.event_type, KEY_LONG_PRESS)
        self.assertEqual(long_press.filter_decision.action, "drop-wake-sequence")

        release = session.process_sample(3)
        self.assertEqual(release.classification.normalized_value, 0x30)
        self.assertEqual(release.driver_event.event_type, KEY_RELEASE)
        self.assertEqual(release.filter_decision.action, "drop-wake-sequence")
        self.assertIsNone(session.filter_state.wake_key)

    def test_media_key_policy_delivers_a_screen_off_key(self) -> None:
        session = KeyInputSession(
            (KEY_NEXT, 2, 3, 4, 5, 6, 7),
            override_value=8,
            screen_state=SCREEN_OFF,
            screen_off_policy=ScreenOffKeyPolicy.MEDIA_KEYS_PASS,
        )

        trace = session.process_sample(0)

        self.assertEqual(trace.filter_decision.action, "deliver")
        self.assertEqual(trace.filter_decision.delivered.key_code, KEY_NEXT)

    def test_override_value_flows_through_driver_and_filter(self) -> None:
        session = KeyInputSession((1, 2, 3, 4, 5, 6, 7), override_value=KEY_NEXT)

        trace = session.process_sample(0x40, override=True)

        self.assertTrue(trace.classification.override_applied)
        self.assertEqual(trace.classification.normalized_value, KEY_NEXT)
        self.assertEqual(trace.filter_decision.delivered.key_code, KEY_NEXT)


if __name__ == "__main__":
    unittest.main()
