"""Small, deterministic ATJ2127 peripheral model for firmware harnesses.

This is not a complete M30 emulator.  It models the parts of the ATJ2127
MMIO surface that are currently useful for reverse-engineering harnesses:
UART FIFO traffic, the Bluetooth power GPIO, a minimal DMA path, and the
watchdog/recovery marker.  Unknown registers remain readable and writable so
new observations can be added without making the model pretend to know more
than it does.

The register names and addresses are based on the public ATJ2127 notes in the
actions_flash project.  The DMA control bit meanings are deliberately exposed
as an explicit host-side operation rather than inferred from incomplete
firmware traces.
"""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field


MRCR = 0xC0000000
CLKENCTL = 0xC0000008
GPIO_A_DAT = 0xC0090000
GPIO_A_OUTEN = 0xC0090008
DMA_CH1_CTL = 0xC00C0030
DMA_CH1_SRC = 0xC00C0034
DMA_CH1_DST = 0xC00C0040
DMA_CH1_LEN = 0xC00C0048
DMA_CH2_CTL = 0xC00C0050
DMA_CH2_DST = 0xC00C0054
DMA_CH2_LEN = 0xC00C0060
UART_CTL = 0xC00F0000
UART_CFG = 0xC00F0004
UART_DAT = 0xC00F0008
UART_STAT = 0xC00F000C
WD_CTL = 0xC012001C

GPIO_BT_POWER = 1 << 19
MRCR_UART_RESET = 1 << 4
UART_TX_EMPTY = 1 << 21
WD_FEED = 1 << 0
ADFU_MARKER = 0xADF0ADF0


class AddressError(ValueError):
    """Raised when a harness tries an unaligned MMIO access."""


@dataclass
class ByteMemory:
    """Sparse byte-addressable memory used by the DMA test path."""

    _data: dict[int, int] = field(default_factory=dict)

    def load(self, address: int, data: bytes) -> None:
        for offset, value in enumerate(data):
            self._data[address + offset] = value

    def read(self, address: int, size: int) -> bytes:
        return bytes(self._data.get(address + offset, 0) for offset in range(size))

    def write(self, address: int, data: bytes) -> None:
        self.load(address, data)


@dataclass
class Atj2127Machine:
    """Register-level ATJ2127 model for deterministic host/QEMU harnesses."""

    memory: ByteMemory = field(default_factory=ByteMemory)
    registers: dict[int, int] = field(default_factory=dict)
    uart_tx: bytearray = field(default_factory=bytearray)
    uart_rx: deque[int] = field(default_factory=deque)
    bt_powered: bool = False
    watchdog_feeds: int = 0
    reset_requested: bool = False
    recovery_requested: bool = False
    ticks_ms: int = 0

    def __post_init__(self) -> None:
        self.registers.setdefault(UART_STAT, UART_TX_EMPTY)

    @staticmethod
    def _check(address: int) -> None:
        if address & 3:
            raise AddressError(f"unaligned MMIO access: 0x{address:08x}")

    def read32(self, address: int) -> int:
        self._check(address)
        if address == UART_DAT:
            return self.uart_rx.popleft() if self.uart_rx else 0
        if address == UART_STAT:
            status = self.registers.get(UART_STAT, 0) | UART_TX_EMPTY
            if self.uart_rx:
                status |= 1
            return status
        return self.registers.get(address, 0)

    def write32(self, address: int, value: int) -> None:
        self._check(address)
        value &= 0xFFFFFFFF
        if address == UART_DAT:
            self.uart_tx.append(value & 0xFF)
            return
        if address == GPIO_A_DAT:
            self.registers[address] = value
            self.bt_powered = bool(value & GPIO_BT_POWER)
            return
        if address == WD_CTL:
            if value & WD_FEED:
                self.watchdog_feeds += 1
            if value == 0x5F:
                self.reset_requested = True
            self.registers[address] = value
            return
        if address == MRCR and value & MRCR_UART_RESET:
            self.uart_tx.clear()
            self.uart_rx.clear()
        self.registers[address] = value

    def inject_uart(self, data: bytes) -> None:
        self.uart_rx.extend(data)

    def dma_uart_tx(self) -> bytes:
        """Execute the modeled channel-1 memory-to-UART transfer once."""
        source = self.read32(DMA_CH1_SRC)
        destination = self.read32(DMA_CH1_DST)
        length = self.read32(DMA_CH1_LEN)
        if destination != UART_DAT:
            raise ValueError(f"DMA destination is not UART_DAT: 0x{destination:08x}")
        payload = self.memory.read(source, length)
        self.uart_tx.extend(payload)
        self.registers[DMA_CH1_CTL] = self.registers.get(DMA_CH1_CTL, 0) | 1
        return payload

    def arm_recovery(self) -> None:
        """Model the documented ADFU marker before a watchdog reset."""
        self.memory.write(0x00130000, ADFU_MARKER.to_bytes(4, "little"))
        self.write32(WD_CTL, 0x5F)
        self.recovery_requested = True

    def step(self, milliseconds: int = 1) -> None:
        if milliseconds < 0:
            raise ValueError("milliseconds must be non-negative")
        self.ticks_ms += milliseconds

