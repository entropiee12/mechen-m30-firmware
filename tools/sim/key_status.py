"""Evidence-backed status and ADC-level path from key.drv.

This model exposes the firmware's register bits and sampled values directly.
It does not assign meanings such as battery or charging to the status values.
"""

from __future__ import annotations

from dataclasses import dataclass, field


STATUS_DEVICE_MASK = 0x180
STATUS_SELECTOR_MASK = 0x08
STATUS_OUTPUT_MASK = 0x20
LRADC_ENABLE_MASK = 0x8000
ADC_SAMPLE_MASK = 0x7F
ADC_SAMPLE_INTERVAL_CALLS = 5
ADC_SAMPLES_PER_STATUS_UPDATE = 10
ADC_LEVEL_THRESHOLDS: tuple[int, ...] = (81, 89, 94, 96, 104, 108)


@dataclass
class KeyStatusRegisters:
    """Current values for the registers read or changed by the status path."""

    adc_sample: int = 0
    device_status: int = 0
    selector_state: int = 0
    lradc_control: int = 0
    control_a: int = 0
    control_b: int = 0

    def __post_init__(self) -> None:
        values = (
            self.adc_sample,
            self.device_status,
            self.selector_state,
            self.lradc_control,
            self.control_a,
            self.control_b,
        )
        if any(not 0 <= value <= 0xFFFFFFFF for value in values):
            raise ValueError("key status register values must fit unsigned 32-bit words")


@dataclass(frozen=True)
class KeyStatusReading:
    """One completed ten-sample status update."""

    status: int
    adc_average: int
    adc_level: int
    samples: tuple[int, ...]


def classify_adc_level(average: int) -> int:
    """Return the firmware's 0..6 level for a seven-bit ADC average."""
    if not 0 <= average <= ADC_SAMPLE_MASK:
        raise ValueError("ADC average must be a seven-bit value")

    return sum(average >= threshold for threshold in ADC_LEVEL_THRESHOLDS)


@dataclass
class KeyStatusSampler:
    """Model the key driver's five-call sampling and ten-sample status update."""

    status: int = 0
    _calls_since_sample: int = 0
    _samples_collected: int = 0
    _sample_window: list[int] = field(default_factory=lambda: [0] * ADC_SAMPLES_PER_STATUS_UPDATE)

    def tick(self, registers: KeyStatusRegisters) -> KeyStatusReading | None:
        """Advance one invocation of the resident status updater.

        The updater samples the ADC on every fifth call. Once ten samples have
        been collected, it updates the status byte and the corresponding MMIO
        bits. Calls between completed batches return ``None``.
        """
        self._validate_registers(registers)
        self._calls_since_sample += 1

        if self._calls_since_sample < ADC_SAMPLE_INTERVAL_CALLS:
            return None

        self._calls_since_sample = 0
        self._sample_window[self._samples_collected] = registers.adc_sample & ADC_SAMPLE_MASK
        self._samples_collected += 1

        if self._samples_collected < ADC_SAMPLES_PER_STATUS_UPDATE:
            return None

        self._samples_collected = 0
        samples = tuple(self._sample_window)
        average = sum(samples) // ADC_SAMPLES_PER_STATUS_UPDATE
        self.status = self._update_status(registers)

        return KeyStatusReading(
            status=self.status,
            adc_average=average,
            adc_level=classify_adc_level(average),
            samples=samples,
        )

    @staticmethod
    def _validate_registers(registers: KeyStatusRegisters) -> None:
        values = (
            registers.adc_sample,
            registers.device_status,
            registers.selector_state,
            registers.lradc_control,
            registers.control_a,
            registers.control_b,
        )
        if any(not 0 <= value <= 0xFFFFFFFF for value in values):
            raise ValueError("key status register values must fit unsigned 32-bit words")

    @staticmethod
    def _update_status(registers: KeyStatusRegisters) -> int:
        if registers.device_status & STATUS_DEVICE_MASK == 0:
            registers.selector_state &= ~STATUS_OUTPUT_MASK
            registers.lradc_control &= ~LRADC_ENABLE_MASK

            return 0

        registers.control_a |= 0x08
        registers.control_b |= 0x20

        if registers.selector_state & STATUS_SELECTOR_MASK:
            registers.selector_state &= ~STATUS_OUTPUT_MASK

            return 2

        registers.selector_state |= STATUS_OUTPUT_MASK

        return 1
