"""Evidence-backed USB mass-storage block-request boundary.

The extracted ``uhost.drv`` contains the ``USBC`` marker, READ(10) and
WRITE(10) opcodes, a block address, and transfer lengths expressed in
512-byte units.  It does not provide enough evidence for complete CBW/CSW
serialization or controller behavior, so this model stops at the request
fields that are visible in the decompilation.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from sd_block_device import SdCardBlockDevice

CBW_SIGNATURE = b"USBC"
READ10_OPCODE = 0x28
WRITE10_OPCODE = 0x2A
READ_DIRECTION = 0x80
BLOCK_SIZE = 512
CONTROL_OPERATION = 0x1F
OBSERVED_STATUS_CODES = frozenset(range(0x80, 0x89))
GUESSED_COMMAND_BLOCK_LENGTH = 31
GUESSED_CDB_LENGTH = 10


@dataclass(frozen=True)
class UsbMassStorageTransportEvent:
    """One raw controller event observed around the block transport."""

    operation: int
    status: int

    def __post_init__(self) -> None:
        if self.operation != CONTROL_OPERATION:
            raise ValueError("operation is not the observed USB control operation")

        if not 0 <= self.status <= 0xFF:
            raise ValueError("status must fit one byte")

        if self.status not in OBSERVED_STATUS_CODES:
            raise ValueError("status is outside the observed USB status range")


@dataclass(frozen=True)
class UsbMassStorageBlockRequest:
    """One observed SCSI block request without transport framing."""

    opcode: int
    block_address: int
    block_count: int
    direction: int | None = None

    def __post_init__(self) -> None:
        if self.opcode not in (READ10_OPCODE, WRITE10_OPCODE):
            raise ValueError("opcode must be READ(10) or WRITE(10)")

        if self.block_address < 0:
            raise ValueError("block_address must be non-negative")

        if self.block_count < 0:
            raise ValueError("block_count must be non-negative")

        if self.direction is not None and not 0 <= self.direction <= 0xFF:
            raise ValueError("direction must fit one byte")

        if self.opcode == READ10_OPCODE and self.direction != READ_DIRECTION:
            raise ValueError("READ(10) requires the observed 0x80 direction")

        if self.opcode == WRITE10_OPCODE and self.direction is not None:
            raise ValueError("WRITE(10) direction remains unresolved")

    @classmethod
    def read10(cls, block_address: int, block_count: int) -> "UsbMassStorageBlockRequest":
        """Build the observed READ(10) request boundary."""
        return cls(
            opcode=READ10_OPCODE,
            block_address=block_address,
            block_count=block_count,
            direction=READ_DIRECTION,
        )

    @classmethod
    def write10(cls, block_address: int, block_count: int) -> "UsbMassStorageBlockRequest":
        """Build a WRITE(10) request without guessing its direction byte."""
        return cls(
            opcode=WRITE10_OPCODE,
            block_address=block_address,
            block_count=block_count,
        )

    @property
    def transfer_bytes(self) -> int:
        """Return the observed block-count-to-byte-count conversion."""
        return self.block_count * BLOCK_SIZE

    def read_from(self, device: SdCardBlockDevice) -> bytes:
        """Read the requested sectors without adding USB transport framing."""
        if self.opcode != READ10_OPCODE:
            raise ValueError("read_from requires a READ(10) request")

        return b"".join(
            device.read_sector(self.block_address + offset)
            for offset in range(self.block_count)
        )

    def write_to(self, device: SdCardBlockDevice, payload: bytes) -> None:
        """Write the requested sectors without assigning a WRITE direction byte."""
        if self.opcode != WRITE10_OPCODE:
            raise ValueError("write_to requires a WRITE(10) request")

        if len(payload) != self.transfer_bytes:
            raise ValueError(
                f"WRITE(10) payload must be exactly {self.transfer_bytes} bytes"
            )

        for offset in range(self.block_count):
            start = offset * BLOCK_SIZE
            device.write_sector(
                self.block_address + offset,
                payload[start : start + BLOCK_SIZE],
            )


@dataclass(frozen=True)
class GuessedUsbMassStorageCommandBlock:
    """Serialize a standard-shaped command block around an observed request.

    The ``USBC`` signature and request fields are observed. The remaining
    31-byte layout follows the common bulk-only command-block shape as an
    explicitly marked hypothesis. No command-status wrapper is generated.
    """

    tag: int
    request: UsbMassStorageBlockRequest
    lun: int = 0

    def __post_init__(self) -> None:
        if not 0 <= self.tag <= 0xFFFFFFFF:
            raise ValueError("command-block tag must fit four bytes")

        if not 0 <= self.lun <= 0xFF:
            raise ValueError("command-block LUN must fit one byte")

        if self.request.block_address > 0xFFFFFFFF:
            raise ValueError("command-block block address must fit four bytes")

        if self.request.block_count > 0xFFFF:
            raise ValueError("command-block block count must fit two bytes")

    @property
    def transfer_bytes(self) -> int:
        return self.request.transfer_bytes

    def encode(self) -> bytes:
        """Return the guessed 31-byte command block without a status phase."""
        cdb = bytearray(16)
        cdb[0] = self.request.opcode
        cdb[2:6] = self.request.block_address.to_bytes(4, "big")
        cdb[7:9] = self.request.block_count.to_bytes(2, "big")
        flags = READ_DIRECTION if self.request.opcode == READ10_OPCODE else 0

        encoded = (
            CBW_SIGNATURE
            + self.tag.to_bytes(4, "little")
            + self.transfer_bytes.to_bytes(4, "little")
            + bytes((flags, self.lun, GUESSED_CDB_LENGTH))
            + bytes(cdb)
        )
        if len(encoded) != GUESSED_COMMAND_BLOCK_LENGTH:
            raise AssertionError("guessed command block has an unexpected length")

        return encoded


@dataclass(frozen=True)
class GuessedUsbMassStorageExchange:
    """One guessed command/data exchange without a CSW or status meaning."""

    request: UsbMassStorageBlockRequest
    command_block: bytes
    data_phase: bytes
    status: int | None = None


@dataclass
class GuessedUsbMassStorageSession:
    """Connect guessed command blocks to the verified SD sector boundary."""

    device: SdCardBlockDevice
    exchanges: list[GuessedUsbMassStorageExchange] = field(default_factory=list)

    def read10(
        self,
        tag: int,
        block_address: int,
        block_count: int,
    ) -> GuessedUsbMassStorageExchange:
        request = UsbMassStorageBlockRequest.read10(block_address, block_count)
        command = GuessedUsbMassStorageCommandBlock(tag, request)
        data = request.read_from(self.device)

        return self._record(request, command, data)

    def write10(
        self,
        tag: int,
        block_address: int,
        block_count: int,
        payload: bytes,
    ) -> GuessedUsbMassStorageExchange:
        request = UsbMassStorageBlockRequest.write10(block_address, block_count)
        command = GuessedUsbMassStorageCommandBlock(tag, request)
        request.write_to(self.device, payload)

        return self._record(request, command, payload)

    def _record(
        self,
        request: UsbMassStorageBlockRequest,
        command: GuessedUsbMassStorageCommandBlock,
        data: bytes,
    ) -> GuessedUsbMassStorageExchange:
        exchange = GuessedUsbMassStorageExchange(
            request=request,
            command_block=command.encode(),
            data_phase=data,
        )
        self.exchanges.append(exchange)

        return exchange
