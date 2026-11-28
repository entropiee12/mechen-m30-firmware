"""Narrow host model of the recovered ``mengine`` message 0x304 writes.

The model keeps the three observed packed bytes unnamed. It does not model
resident services, shuffle effects, or playback/DSP behavior.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass
class MengineState304:
    """Represent the three RAM bytes written by the 0x304 handler.

    The persisted-record initializer sets offsets +0x2b and +0x2c to 0 and 2
    when the record is absent or invalid. Offset +0x2e starts at the supplied
    value as unknown until a message writes it, because its initialization was
    not recovered.
    """

    field_2b: int = 0
    field_2c: int = 2
    field_2e: int | None = None

    def __post_init__(self) -> None:
        self._validate_byte(self.field_2b, "field_2b")
        self._validate_byte(self.field_2c, "field_2c")
        if self.field_2e is not None:
            self._validate_byte(self.field_2e, "field_2e")

    def apply_message(self, payload: int, status: int) -> bool:
        """Apply observed masks; return whether status 2 requests snapshot 0x1c.

        Only statuses 0, 1, and 2 have been executed in p-code. The return
        value records the observed status-2 transition condition; it does not
        execute the external ``mmm_mp`` service.
        """
        self._validate_byte(payload, "payload")

        if status not in (0, 1, 2):
            raise ValueError("only observed message handler statuses 0, 1, and 2 are modeled")

        previous_selection = self.field_2c
        next_field_2c = payload & 0x0F

        self.field_2b = payload & 0x30
        self.field_2c = next_field_2c
        self.field_2e = payload & 0xC0

        return status == 2 and next_field_2c == 3 and previous_selection != 3

    @staticmethod
    def _validate_byte(value: int, name: str) -> None:
        if not 0 <= value <= 0xFF:
            raise ValueError(f"{name} must fit an unsigned byte")
