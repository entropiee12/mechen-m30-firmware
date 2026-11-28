"""Evidence-backed ADC classification from the resident M30 key driver.

The driver supplies the normalized value table from RAM, so this module keeps
those values caller-provided and does not assign physical button names.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence

from key_filter import (
    KEY_DOWN,
    KEY_HOLD,
    KEY_LONG_PRESS,
    KEY_RELEASE,
    KeyEvent,
)


ADC_SAMPLE_MASK = 0x7F
ADC_THRESHOLDS: tuple[int, ...] = (0x03, 0x18, 0x2A, 0x3A, 0x4A, 0x5F, 0x6F)
UNMAPPED_KEY_VALUE = 0xFF
SPECIAL_RELEASE_INPUT = 0x30
SPECIAL_PLAY_INPUT = 0x32
SPECIAL_CARD_INPUT = 0x34
SPECIAL_PLAY_KEY = 0x0C
SPECIAL_CARD_KEY = 0x0D


@dataclass(frozen=True)
class AdcClassification:
    """One normalized result without assigning a physical key name."""

    sampled_value: int
    normalized_value: int
    override_applied: bool


def classify_adc_sample(
    sample: int,
    normalized_values: Sequence[int],
    *,
    override: bool = False,
    override_value: int | None = None,
) -> AdcClassification:
    """Apply the recovered threshold loop to one hardware sample.

    The firmware masks the hardware value to seven bits, returns the first
    RAM-table value whose threshold is greater than the sample, and returns
    ``0xff`` when no threshold matches. Its override path replaces the sample
    with zero and returns a separate RAM-table value; that value is explicit
    here because the driver does not initialize the table itself.
    """
    if not 0 <= sample <= 0xFF:
        raise ValueError("ADC sample must fit an unsigned byte")
    if len(normalized_values) != len(ADC_THRESHOLDS):
        raise ValueError("key normalization table must contain seven values")
    if any(not 0 <= value <= 0xFF for value in normalized_values):
        raise ValueError("normalized key values must fit an unsigned byte")
    if override_value is not None and not 0 <= override_value <= 0xFF:
        raise ValueError("override key value must fit an unsigned byte")

    if override:
        if override_value is None:
            raise ValueError("override path requires an explicit table value")

        return AdcClassification(0, override_value, True)

    sampled_value = sample & ADC_SAMPLE_MASK
    for threshold, normalized_value in zip(ADC_THRESHOLDS, normalized_values):
        if sampled_value < threshold:
            return AdcClassification(sampled_value, normalized_value, False)

    return AdcClassification(sampled_value, UNMAPPED_KEY_VALUE, False)


@dataclass
class KeyDriverRepeatState:
    """Translate normalized driver values into application key events."""

    previous_value: int = 0
    repeat_count: int = 0

    def process(self, normalized_value: int) -> KeyEvent | None:
        """Emit the event proven by the resident repeat-state function.

        A new value emits ``KEY_DOWN`` immediately.  The fifth consecutive
        sample emits ``KEY_LONG_PRESS`` and each later sample emits
        ``KEY_HOLD``.  The three special values are kept explicit because the
        resident function handles them before the generic value path.
        """
        if not 0 <= normalized_value <= 0xFF:
            raise ValueError("normalized key value must fit an unsigned byte")

        if normalized_value == self.previous_value:
            self.repeat_count += 1

            if self.repeat_count == 5:
                return KeyEvent(self.previous_value, KEY_LONG_PRESS)

            if self.repeat_count >= 6:
                return KeyEvent(self.previous_value, KEY_HOLD)

            return None

        if normalized_value == SPECIAL_RELEASE_INPUT:
            event = KeyEvent(self.previous_value, KEY_RELEASE)
            self.previous_value = 0
            self.repeat_count = 0

            return event

        if normalized_value == SPECIAL_PLAY_INPUT:
            self.previous_value = 0
            self.repeat_count = 0

            return KeyEvent(SPECIAL_PLAY_KEY, KEY_DOWN)

        if normalized_value == SPECIAL_CARD_INPUT:
            self.previous_value = 0
            self.repeat_count = 0

            return KeyEvent(SPECIAL_CARD_KEY, KEY_DOWN)

        self.previous_value = normalized_value
        self.repeat_count = 1

        return KeyEvent(normalized_value, KEY_DOWN)
