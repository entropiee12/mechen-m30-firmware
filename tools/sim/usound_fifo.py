"""Evidence-backed word-port transfer boundary from ``usound.drv``.

The driver copies whole words through two MMIO locations.  The model keeps
the port semantics generic: it does not claim that the registers are a USB
FIFO or that the resulting bytes are PCM.
"""

from __future__ import annotations

from dataclasses import dataclass, field


DATA_PORT_ADDRESS = 0xC0080084
ALTERNATE_PORT_ADDRESS = 0xC0080088
TRANSFER_GATE_ADDRESS = 0x9FC33E12

READ_DATA_MODE = 2
WRITE_DATA_MODE = 1
READ_ALTERNATE_MODE = 4


@dataclass
class WordPortTransfer:
    """Model the observed ``param_1`` modes of ``FUN_bfc318cc``."""

    data_reads: list[int] = field(default_factory=list)
    alternate_reads: list[int] = field(default_factory=list)
    transfer_gate: int = 0
    data_writes: list[int] = field(default_factory=list)

    def transfer(self, mode: int, words: list[int], byte_count: int) -> int:
        if byte_count < 0:
            raise ValueError("byte_count must be non-negative")
        word_count = byte_count >> 2
        if len(words) < word_count:
            raise ValueError("destination/source word buffer is too short")

        if mode == READ_DATA_MODE:
            if self.transfer_gate != 1:
                for index in range(word_count):
                    words[index] = self._next(self.data_reads)
        elif mode == WRITE_DATA_MODE:
            self.data_writes.extend(words[:word_count])
        elif mode == READ_ALTERNATE_MODE:
            for index in range(word_count):
                words[index] = self._next(self.alternate_reads)
        return word_count

    @staticmethod
    def _next(values: list[int]) -> int:
        if not values:
            return 0
        return values.pop(0)
