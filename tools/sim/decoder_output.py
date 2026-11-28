"""Fail-closed model of the recovered decoder write-callback boundary."""

from __future__ import annotations

from dataclasses import dataclass


DECODER_SETUP_COMMAND = 0x2A
REJECT_STATUS = -1
REJECTING_CALLBACK_POINTER = 0x6015110F


@dataclass(frozen=True)
class DecoderWriteResult:
    status: int
    accepted_bytes: int
    reason: str


@dataclass
class DecoderWriteBoundary:
    """Reject decoder writes until a proven callback contract exists.

    The community image initializes the command's callback slot with a
    defined function that returns ``0xffffffff``. The host model therefore
    keeps both the unconfigured and known-rejecting states fail-closed and
    never counts rejected bytes as delivered PCM.
    """

    callback_pointer: int | None = None

    def configure_rejecting_callback(self) -> None:
        self.callback_pointer = REJECTING_CALLBACK_POINTER

    def write(self, payload: bytes) -> DecoderWriteResult:
        if self.callback_pointer is None:
            reason = "unconfigured-callback"
        elif self.callback_pointer == REJECTING_CALLBACK_POINTER:
            reason = "rejecting-callback"
        else:
            raise ValueError("decoder callback pointer is not evidence-backed")

        return DecoderWriteResult(REJECT_STATUS, 0, reason)
