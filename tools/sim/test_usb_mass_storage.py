import unittest

from sd_block_device import SdCardBlockDevice
from usb_mass_storage import (
    BLOCK_SIZE,
    CBW_SIGNATURE,
    CONTROL_OPERATION,
    GUESSED_COMMAND_BLOCK_LENGTH,
    OBSERVED_STATUS_CODES,
    READ10_OPCODE,
    READ_DIRECTION,
    WRITE10_OPCODE,
    UsbMassStorageBlockRequest,
    GuessedUsbMassStorageCommandBlock,
    GuessedUsbMassStorageSession,
    UsbMassStorageTransportEvent,
)


class UsbMassStorageBlockRequestTests(unittest.TestCase):
    def test_transport_event_preserves_observed_raw_bytes(self):
        events = tuple(
            UsbMassStorageTransportEvent(CONTROL_OPERATION, status)
            for status in sorted(OBSERVED_STATUS_CODES)
        )

        self.assertEqual(
            tuple(event.status for event in events),
            tuple(sorted(OBSERVED_STATUS_CODES)),
        )

    def test_transport_event_rejects_unobserved_operation_or_status(self):
        with self.assertRaises(ValueError):
            UsbMassStorageTransportEvent(0x00, 0x80)

        with self.assertRaises(ValueError):
            UsbMassStorageTransportEvent(CONTROL_OPERATION, 0x89)

    def test_read10_preserves_observed_fields_and_block_size(self):
        request = UsbMassStorageBlockRequest.read10(0x1234, 3)
        self.assertEqual(request.opcode, READ10_OPCODE)
        self.assertEqual(request.block_address, 0x1234)
        self.assertEqual(request.block_count, 3)
        self.assertEqual(request.direction, READ_DIRECTION)
        self.assertEqual(request.transfer_bytes, 3 * BLOCK_SIZE)

    def test_write10_keeps_direction_unresolved(self):
        request = UsbMassStorageBlockRequest.write10(7, 2)
        self.assertEqual(request.opcode, WRITE10_OPCODE)
        self.assertIsNone(request.direction)
        self.assertEqual(request.transfer_bytes, 1024)

    def test_unknown_opcode_is_rejected(self):
        with self.assertRaises(ValueError):
            UsbMassStorageBlockRequest(0x12, 0, 1)

    def test_negative_block_fields_are_rejected(self):
        with self.assertRaises(ValueError):
            UsbMassStorageBlockRequest.read10(-1, 1)
        with self.assertRaises(ValueError):
            UsbMassStorageBlockRequest.read10(0, -1)

    def test_write_direction_is_not_invented(self):
        with self.assertRaises(ValueError):
            UsbMassStorageBlockRequest(
                opcode=WRITE10_OPCODE,
                block_address=0,
                block_count=1,
                direction=0,
            )

    def test_read10_moves_sector_bytes_from_sd_device(self):
        card = SdCardBlockDevice(sector_count=3)
        first = bytes([0x11]) * BLOCK_SIZE
        second = bytes([0x22]) * BLOCK_SIZE
        card.write_sector(1, first)
        card.write_sector(2, second)

        payload = UsbMassStorageBlockRequest.read10(1, 2).read_from(card)

        self.assertEqual(payload, first + second)

    def test_write10_moves_exact_sector_payload_to_sd_device(self):
        card = SdCardBlockDevice(sector_count=2)
        payload = bytes([0x33]) * (2 * BLOCK_SIZE)
        request = UsbMassStorageBlockRequest.write10(0, 2)

        request.write_to(card, payload)

        self.assertEqual(card.read_sector(0), bytes([0x33]) * BLOCK_SIZE)
        self.assertEqual(card.read_sector(1), bytes([0x33]) * BLOCK_SIZE)

    def test_request_direction_and_payload_size_are_checked(self):
        card = SdCardBlockDevice(sector_count=1)
        read_request = UsbMassStorageBlockRequest.read10(0, 1)
        write_request = UsbMassStorageBlockRequest.write10(0, 1)

        with self.assertRaises(ValueError):
            read_request.write_to(card, bytes(BLOCK_SIZE))
        with self.assertRaises(ValueError):
            write_request.read_from(card)
        with self.assertRaises(ValueError):
            write_request.write_to(card, bytes(BLOCK_SIZE - 1))

    def test_guessed_command_block_serializes_observed_read_fields(self):
        request = UsbMassStorageBlockRequest.read10(0x12345678, 3)
        command = GuessedUsbMassStorageCommandBlock(0xAABBCCDD, request)

        encoded = command.encode()

        self.assertEqual(len(encoded), GUESSED_COMMAND_BLOCK_LENGTH)
        self.assertEqual(encoded[:4], CBW_SIGNATURE)
        self.assertEqual(encoded[4:8], bytes.fromhex("ddccbbaa"))
        self.assertEqual(int.from_bytes(encoded[8:12], "little"), 3 * BLOCK_SIZE)
        self.assertEqual(encoded[12:15], bytes((0x80, 0, 10)))
        self.assertEqual(encoded[15], READ10_OPCODE)
        self.assertEqual(encoded[17:21], bytes.fromhex("12345678"))
        self.assertEqual(encoded[22:24], bytes.fromhex("0003"))

    def test_guessed_command_block_uses_zero_write_flags_without_claiming_csw(self):
        request = UsbMassStorageBlockRequest.write10(7, 2)
        command = GuessedUsbMassStorageCommandBlock(1, request)

        encoded = command.encode()

        self.assertEqual(encoded[12:15], bytes((0, 0, 10)))
        self.assertEqual(encoded[15], WRITE10_OPCODE)
        self.assertEqual(encoded[17:21], bytes.fromhex("00000007"))
        self.assertEqual(encoded[22:24], bytes.fromhex("0002"))

    def test_guessed_command_block_rejects_fields_outside_cdb_widths(self):
        with self.assertRaises(ValueError):
            GuessedUsbMassStorageCommandBlock(
                1,
                UsbMassStorageBlockRequest.read10(0x1_0000_0000, 1),
            )

        with self.assertRaises(ValueError):
            GuessedUsbMassStorageCommandBlock(
                1,
                UsbMassStorageBlockRequest.read10(0, 0x1_0000),
            )

    def test_guessed_session_reads_command_and_sector_data_together(self):
        card = SdCardBlockDevice(sector_count=2)
        payload = bytes([0x44]) * BLOCK_SIZE
        card.write_sector(1, payload)
        session = GuessedUsbMassStorageSession(card)

        exchange = session.read10(0x21, 1, 1)

        self.assertEqual(exchange.data_phase, payload)
        self.assertEqual(exchange.command_block[:4], CBW_SIGNATURE)
        self.assertEqual(exchange.command_block[4:8], bytes.fromhex("21000000"))
        self.assertIsNone(exchange.status)
        self.assertEqual(session.exchanges, [exchange])

    def test_guessed_session_writes_command_and_sector_data_together(self):
        card = SdCardBlockDevice(sector_count=2)
        payload = bytes([0x55]) * (2 * BLOCK_SIZE)
        session = GuessedUsbMassStorageSession(card)

        exchange = session.write10(0x22, 0, 2, payload)

        self.assertEqual(card.read_sector(0), payload[:BLOCK_SIZE])
        self.assertEqual(card.read_sector(1), payload[BLOCK_SIZE:])
        self.assertEqual(exchange.data_phase, payload)
        self.assertEqual(exchange.command_block[12:15], bytes((0, 0, 10)))
        self.assertIsNone(exchange.status)

    def test_guessed_session_does_not_record_failed_writes(self):
        session = GuessedUsbMassStorageSession(SdCardBlockDevice(sector_count=1))

        with self.assertRaises(ValueError):
            session.write10(0x23, 0, 1, b"short")

        self.assertEqual(session.exchanges, [])


if __name__ == "__main__":
    unittest.main()
