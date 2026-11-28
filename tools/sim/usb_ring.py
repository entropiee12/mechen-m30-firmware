"""Evidence-backed generic ring-consumer boundary from ``usound.drv``.

This is deliberately not labelled as the firmware's PCM/DMA implementation:
the producer and the meaning of the callback are unresolved.  It models only
the behaviour visible in the decompiled ``FUN_bfc31654`` entry.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Callable


RING_CAPACITY = 0x1000
SHORT_READ_RESET_THRESHOLD = 100

# Literal-pool targets recovered from usound.drv around bfc31728.  These are
# addresses in the device image, not host pointers; they document the proven
# state layout without pretending the host model has the target RAM map.
RING_ADDRESS = 0x9FC34000
READ_POINTER_ADDRESS = 0x9FC33DEC
AVAILABLE_BYTES_ADDRESS = 0x9FC33DF0
SHORT_READ_COUNTER_ADDRESS = 0x9FC33DF4
RESET_SIGNAL_ADDRESS = 0x9FC33E13
COPY_CALLBACK_ADDRESS = 0xBFC326A9


@dataclass
class RingConsumer:
    """Consume available bytes from a 4 KiB circular region."""

    available: int = 0
    read_offset: int = 0
    short_read_count: int = 0
    reset_signal: int = 0

    def __post_init__(self) -> None:
        if not 0 <= self.available <= RING_CAPACITY:
            raise ValueError("available must fit inside the 4 KiB ring")
        if not 0 <= self.read_offset < RING_CAPACITY:
            raise ValueError("read_offset must be a 4 KiB ring offset")

    def consume(
        self,
        requested: int,
        copy: Callable[[int, int], None],
    ) -> int:
        """Copy ``requested`` bytes, returning zero on insufficient data.

        ``copy(offset, length)`` is called once or twice for a wrapped read.
        The producer is intentionally not modelled; callers may increase
        ``available`` through their own evidence-backed adapter.
        """

        if requested < 0:
            raise ValueError("requested must be non-negative")
        if requested > self.available:
            self.short_read_count += 1
            if self.short_read_count >= SHORT_READ_RESET_THRESHOLD:
                self.reset_signal = 0
                self.short_read_count = 0
            return 0

        self.short_read_count = 0
        if requested == 0:
            return 0

        first = min(requested, RING_CAPACITY - self.read_offset)
        copy(self.read_offset, first)
        second = requested - first
        if second:
            copy(0, second)
        self.available -= requested
        # FUN_bfc31654 does not retain the remainder after a wrap.  Its
        # recovered branch is `if (read_offset > 0xfff) read_offset = 0`.
        self.read_offset += requested
        if self.read_offset >= RING_CAPACITY:
            self.read_offset = 0
        return requested

    def consume_bytes(self, ring: bytes | bytearray, requested: int) -> bytes:
        """Read bytes from the verified ring layout and advance its state."""
        if len(ring) != RING_CAPACITY:
            raise ValueError("ring source must be exactly 0x1000 bytes")

        output = bytearray()

        def copy(offset: int, length: int) -> None:
            output.extend(ring[offset : offset + length])

        self.consume(requested, copy)
        return bytes(output)
