"""Deterministic host stream joining the observed USB-audio contracts."""

from __future__ import annotations

from dataclasses import dataclass, field

from usb_audio import UsbAudioContract
from usb_ring import RING_CAPACITY, RingConsumer


@dataclass
class UsbAudioStream:
    """Accept bounded USB packets and expose a bounded PCM byte stream.

    This models packet framing and the observed circular-consumer boundary.
    It does not claim to emulate the unresolved firmware producer callback,
    codec, DAC, or hardware DMA engine.
    """

    contract: UsbAudioContract = field(default_factory=UsbAudioContract)
    ring: RingConsumer = field(default_factory=RingConsumer)
    storage: bytearray = field(default_factory=lambda: bytearray(RING_CAPACITY))
    write_offset: int = 0

    def __post_init__(self) -> None:
        if len(self.storage) != RING_CAPACITY:
            raise ValueError("storage must match the observed ring capacity")
        if not 0 <= self.write_offset < RING_CAPACITY:
            raise ValueError("write_offset must be a ring offset")
        if self.ring.available == 0:
            self.ring.read_offset = self.write_offset

    @property
    def available(self) -> int:
        return self.ring.available

    def set_interface(self, alternate_setting: int, *, interface_index: int = 1) -> bool:
        """Apply the observed SET_INTERFACE ring reset for interface 1.

        The real handler resets its producer offset, consumer offset, queued
        byte count, and reset signal only for alternate setting 1. Other
        alternate settings are acknowledged without changing those fields.
        The PCM storage and short-read counter are not cleared by the observed
        handler path.
        """

        if not 0 <= alternate_setting <= 0xFFFF:
            raise ValueError("alternate_setting must fit an unsigned 16-bit field")

        if interface_index != 1:
            raise NotImplementedError("only SET_INTERFACE for interface 1 is recovered")

        if alternate_setting != 1:
            return False

        self.write_offset = 0
        self.ring.read_offset = 0
        self.ring.available = 0
        self.ring.reset_signal = 0
        return True

    def submit_packet(self, payload: bytes) -> int:
        """Queue one frame-aligned packet up to the descriptor maximum."""
        frames = self.contract.validate_packet(payload)
        if len(payload) > RING_CAPACITY - self.ring.available:
            raise BufferError("USB audio ring does not have enough free space")

        first = min(len(payload), RING_CAPACITY - self.write_offset)
        self.storage[self.write_offset : self.write_offset + first] = payload[:first]
        second = len(payload) - first
        if second:
            self.storage[:second] = payload[first:]
        self.write_offset = (self.write_offset + len(payload)) % RING_CAPACITY
        self.ring.available += len(payload)
        return frames

    def read(self, requested: int) -> bytes:
        """Read a complete byte range, or return empty on short input."""
        result = bytearray()

        def copy(offset: int, size: int) -> None:
            result.extend(self.storage[offset : offset + size])

        consumed = self.ring.consume(requested, copy)
        if consumed != requested:
            return b""
        return bytes(result)
