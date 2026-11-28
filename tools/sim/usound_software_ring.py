"""Host model of the recovered 0x6e40-byte usound software-ring writer."""

from __future__ import annotations

from dataclasses import dataclass, field

from usound_format import expand_three_to_four


RING_CAPACITY = 0x6E40
EXPANDED_READY_THRESHOLD = 0x1B90
DIRECT_READY_THRESHOLD = 0x3720
DMA_COMPLETION_BYTES = 0x3720
DMA_CONTROL_COMPLETION_BITS = 0x404
CLOCK_CONTROL_MASK = 0x03
CLOCK_CONTROL_CLEAR_THRESHOLD = 4410
CLOCK_CONTROL_SET_THRESHOLD = 9703
DMA_RING_ADDRESS = 0x9FC24000
DMA_COMMON_CONTROL_BITS = 0x404
DMA_START_BIT = 0x01
DMA_16BIT_CHANNEL_CONTROL = 0x4B02
DMA_24BIT_CHANNEL_CONTROL = 0x8B02
DMA_16BIT_SIZE_REGISTER = 14112
DMA_24BIT_SIZE_REGISTER = 7056


@dataclass(frozen=True)
class DmaStartRegisters:
    """Recovered register values written when the software ring is started."""

    address: int
    channel_control: int
    size_register: int
    common_control_04: int
    common_control_08: int


@dataclass(frozen=True)
class DmaCompletionResult:
    """Observed register state after one USB Audio DMA completion."""

    clock_control: int
    dma_control: int
    empty_branch: bool


def build_dma_start_registers(
    format_flag: int,
    *,
    common_control_04: int = 0,
    common_control_08: int = 0,
) -> DmaStartRegisters:
    """Return the observed register writes for the ring's DMA start path.

    Only ``format_flag == 1`` selects the 24-bit register values. The size
    register is kept as a raw value; its hardware unit is unresolved.
    """

    if not 0 <= format_flag <= 0xFF:
        raise ValueError("format_flag must fit one byte")

    if not 0 <= common_control_04 <= 0xFFFFFFFF:
        raise ValueError("common_control_04 must fit a 32-bit register")

    if not 0 <= common_control_08 <= 0xFFFFFFFF:
        raise ValueError("common_control_08 must fit a 32-bit register")

    if format_flag == 1:
        channel_control = DMA_24BIT_CHANNEL_CONTROL
        size_register = DMA_24BIT_SIZE_REGISTER
    else:
        channel_control = DMA_16BIT_CHANNEL_CONTROL
        size_register = DMA_16BIT_SIZE_REGISTER

    return DmaStartRegisters(
        address=DMA_RING_ADDRESS,
        channel_control=channel_control | DMA_START_BIT,
        size_register=size_register,
        common_control_04=common_control_04 | DMA_COMMON_CONTROL_BITS,
        common_control_08=common_control_08 | DMA_COMMON_CONTROL_BITS,
    )


@dataclass
class SoftwareRing:
    """Model the byte producer and ready latch, without hardware I/O.

    ``ready_signal`` corresponds to the shared ``0x9fc33e13`` byte: the
    producer sets it at the format-specific fill threshold, and the observed
    empty DMA-completion branch clears it.
    """

    data: bytearray = field(default_factory=lambda: bytearray(RING_CAPACITY))
    write_offset: int = 0
    available: int = 0
    ready_signal: int = 0

    def __post_init__(self) -> None:
        if len(self.data) != RING_CAPACITY:
            raise ValueError("software ring must be exactly 0x6e40 bytes")
        if not 0 <= self.write_offset < RING_CAPACITY:
            raise ValueError("write_offset must be a ring offset")
        if not 0 <= self.available <= RING_CAPACITY:
            raise ValueError("available must fit inside the software ring")

    def produce(self, source: bytes, *, expanded: bool) -> bool:
        """Write one hardware block and return whether the ready edge fired."""

        output = expand_three_to_four(source) if expanded else source
        output_size = len(output)
        if output_size > RING_CAPACITY:
            raise ValueError("one produced block cannot exceed the ring")

        first = min(output_size, RING_CAPACITY - self.write_offset)
        self.data[self.write_offset : self.write_offset + first] = output[:first]
        second = output_size - first
        if second:
            self.data[:second] = output[first:]

        self.write_offset = (self.write_offset + output_size) % RING_CAPACITY
        self.available += output_size
        if self.available > RING_CAPACITY:
            # This is the observed overrun policy, not a generic clamp.
            self.available = self.write_offset

        threshold = EXPANDED_READY_THRESHOLD if expanded else DIRECT_READY_THRESHOLD
        if self.available >= threshold and self.ready_signal == 0:
            self.ready_signal = 1
            return True
        return False

    def complete_dma(self, *, clock_control: int, dma_control: int) -> DmaCompletionResult:
        """Apply the recovered ``FUN_bfc3125c`` fill and register policy.

        The handler subtracts one 0x3720-byte DMA block from ``available``.
        It does not read the 16/24-bit format flag; that flag is used by other
        driver functions when they configure the DMA transfer.
        """

        if not 0 <= clock_control <= 0xFFFFFFFF:
            raise ValueError("clock_control must fit a 32-bit register")

        if not 0 <= dma_control <= 0xFFFFFFFF:
            raise ValueError("dma_control must fit a 32-bit register")

        remaining = self.available - DMA_COMPLETION_BYTES
        next_dma_control = dma_control | DMA_CONTROL_COMPLETION_BITS

        if remaining < 1:
            self.available = 0
            self.ready_signal = 0
            return DmaCompletionResult(
                clock_control=clock_control,
                dma_control=next_dma_control,
                empty_branch=True,
            )

        self.available = remaining

        if remaining < CLOCK_CONTROL_CLEAR_THRESHOLD:
            next_clock_control = clock_control & ~CLOCK_CONTROL_MASK
        elif remaining >= CLOCK_CONTROL_SET_THRESHOLD:
            next_clock_control = clock_control | CLOCK_CONTROL_MASK
        else:
            next_clock_control = clock_control

        return DmaCompletionResult(
            clock_control=next_clock_control,
            dma_control=next_dma_control,
            empty_branch=False,
        )
