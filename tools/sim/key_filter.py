"""Host model of the M30 application's common key filter.

The resident key driver produces normalized events, but the applications all
run those events through the same filter in bank ``0x48694600``.  This module
models that application boundary only.  It deliberately does not assign
physical buttons to the driver's normalized values or claim to emulate the
unknown MMIO sampling and debounce registers.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


KEY_PLAY_PAUSE = 0x01
KEY_PREVIOUS = 0x02
KEY_NEXT = 0x03
KEY_VOLUME_UP = 0x05
KEY_VOLUME_DOWN = 0x06
KEY_POWER = 0x81

KEYPASS_KEYS = frozenset(
    {
        KEY_PLAY_PAUSE,
        KEY_PREVIOUS,
        KEY_NEXT,
        KEY_VOLUME_UP,
        KEY_VOLUME_DOWN,
    }
)

SCREEN_ON = 0
SCREEN_OFF = 2

KEY_DOWN = 0x2000
KEY_LONG_PRESS = 0x1000
KEY_HOLD = 0x0800
KEY_RELEASE = 0x0400

WAKE_MESSAGE = 0x106


class ScreenOffKeyPolicy(str, Enum):
    """Observed application policy for non-power keys while the screen is off."""

    WAKE_ONLY = "wake-only"
    MEDIA_KEYS_PASS = "media-keys-pass"


@dataclass(frozen=True)
class KeyEvent:
    """One normalized key event passed to the application filter."""

    key_code: int
    event_type: int

    def __post_init__(self) -> None:
        if not 0 <= self.key_code <= 0xFF:
            raise ValueError("key_code must fit in one byte")
        if self.event_type not in (KEY_DOWN, KEY_LONG_PRESS, KEY_HOLD, KEY_RELEASE):
            raise ValueError("unsupported key event type")


@dataclass(frozen=True)
class FilterDecision:
    """Observable result of one application-level filter invocation."""

    action: str
    delivered: KeyEvent | None
    wake_message: int | None
    idle_counters_reset: bool
    wake_key: int | None


@dataclass
class KeyFilterState:
    """Model an observed screen-off policy for normalized key events."""

    screen_state: int = SCREEN_ON
    wake_key: int | None = None
    screen_off_policy: ScreenOffKeyPolicy = ScreenOffKeyPolicy.WAKE_ONLY

    def __post_init__(self) -> None:
        if self.screen_state not in (SCREEN_ON, SCREEN_OFF):
            raise ValueError("screen_state must be SCREEN_ON or SCREEN_OFF")

    def process(self, event: KeyEvent, *, patched: bool = False) -> FilterDecision:
        """Filter one event without mutating the physical-key or LCD state.

        With the screen on, the stock path delivers the event. With the screen
        off, the wake-only policy turns non-power events into wake message
        ``0x106`` and remembers the key so its long-press/release sequence is
        discarded. The media-keys-pass policy routes only the five normalized
        media keys through the normal screen-on path. ``patched`` remains a
        compatibility alias for that policy.
        """
        if self.screen_state == SCREEN_ON or event.key_code == KEY_POWER:
            return self._deliver(event)

        if self.wake_key == event.key_code:
            if event.event_type == KEY_RELEASE:
                self.wake_key = None

            return FilterDecision(
                action="drop-wake-sequence",
                delivered=None,
                wake_message=None,
                idle_counters_reset=False,
                wake_key=self.wake_key,
            )

        media_keys_pass = (
            patched
            or self.screen_off_policy is ScreenOffKeyPolicy.MEDIA_KEYS_PASS
        )
        if media_keys_pass and event.key_code in KEYPASS_KEYS:
            return self._deliver(event)

        self.wake_key = event.key_code
        return FilterDecision(
            action="wake",
            delivered=None,
            wake_message=WAKE_MESSAGE,
            idle_counters_reset=False,
            wake_key=self.wake_key,
        )

    def _deliver(self, event: KeyEvent) -> FilterDecision:
        return FilterDecision(
            action="deliver",
            delivered=event,
            wake_message=None,
            idle_counters_reset=event.event_type == KEY_DOWN,
            wake_key=self.wake_key,
        )
