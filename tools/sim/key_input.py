"""End-to-end host model for the recovered M30 key input boundary."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Sequence

from key_driver import (
    AdcClassification,
    KeyDriverRepeatState,
    classify_adc_sample,
)
from key_filter import (
    SCREEN_ON,
    FilterDecision,
    KeyEvent,
    KeyFilterState,
    ScreenOffKeyPolicy,
)


@dataclass(frozen=True)
class KeyInputTrace:
    """One ADC sample and its observed driver and application outcomes."""

    classification: AdcClassification
    driver_event: KeyEvent | None
    filter_decision: FilterDecision | None


@dataclass
class KeyInputSession:
    """Connect ADC classification, repeat handling, and the application filter."""

    normalized_values: Sequence[int]
    override_value: int
    screen_state: int = SCREEN_ON
    screen_off_policy: ScreenOffKeyPolicy = ScreenOffKeyPolicy.WAKE_ONLY
    repeat_state: KeyDriverRepeatState = field(init=False)
    filter_state: KeyFilterState = field(init=False)

    def __post_init__(self) -> None:
        self.normalized_values = tuple(self.normalized_values)
        classify_adc_sample(
            0,
            self.normalized_values,
            override_value=self.override_value,
        )
        self.repeat_state = KeyDriverRepeatState()
        self.filter_state = KeyFilterState(
            screen_state=self.screen_state,
            screen_off_policy=self.screen_off_policy,
        )

    def process_sample(self, sample: int, *, override: bool = False) -> KeyInputTrace:
        """Pass one sampled byte through each proven software boundary."""
        classification = classify_adc_sample(
            sample,
            self.normalized_values,
            override=override,
            override_value=self.override_value,
        )
        driver_event = self.repeat_state.process(classification.normalized_value)
        filter_decision = None

        if driver_event is not None:
            filter_decision = self.filter_state.process(driver_event)

        return KeyInputTrace(
            classification=classification,
            driver_event=driver_event,
            filter_decision=filter_decision,
        )
