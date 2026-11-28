"""Evidence-backed byte boundary for the recovered DAC control bus."""

from __future__ import annotations

from dataclasses import dataclass, field


BITS_PER_BYTE = 8


@dataclass
class DacByteBus:
    """Exchange complete bytes without inventing codec or GPIO semantics."""

    incoming: bytearray = field(default_factory=bytearray)
    transmitted: bytearray = field(default_factory=bytearray)

    def queue_incoming(self, payload: bytes) -> None:
        """Make complete bytes available to the recovered receive helper."""
        self.incoming.extend(payload)

    def send(self, payload: bytes) -> int:
        """Record a byte sequence sent by the recovered transmit helper."""
        self.transmitted.extend(payload)
        return len(payload)

    def receive(self, byte_count: int) -> bytes:
        """Consume exactly ``byte_count`` available bytes from the bus."""
        if byte_count < 0:
            raise ValueError("byte_count must be non-negative")

        if byte_count > len(self.incoming):
            raise BufferError("DAC bus does not have enough incoming bytes")

        payload = bytes(self.incoming[:byte_count])
        del self.incoming[:byte_count]
        return payload

    @staticmethod
    def bit_count(byte_count: int) -> int:
        """Return the recovered eight-bits-per-byte transfer count."""
        if byte_count < 0:
            raise ValueError("byte_count must be non-negative")

        return byte_count * BITS_PER_BYTE
