import unittest

from dac_bus import BITS_PER_BYTE, DacByteBus


class DacByteBusTests(unittest.TestCase):
    def test_send_preserves_complete_byte_sequence(self):
        bus = DacByteBus()

        self.assertEqual(bus.send(bytes((0x10, 0x80, 0xFF))), 3)
        self.assertEqual(bus.transmitted, bytearray((0x10, 0x80, 0xFF)))

    def test_receive_consumes_complete_byte_sequence(self):
        bus = DacByteBus()
        bus.queue_incoming(bytes((0x12, 0x34, 0x56)))

        self.assertEqual(bus.receive(2), bytes((0x12, 0x34)))
        self.assertEqual(bus.receive(1), bytes((0x56,)))

    def test_receive_rejects_short_input_without_consuming_it(self):
        bus = DacByteBus()
        bus.queue_incoming(bytes((0x12,)))

        with self.assertRaises(BufferError):
            bus.receive(2)

        self.assertEqual(bus.incoming, bytearray((0x12,)))

    def test_bit_count_uses_the_recovered_eight_bit_transfer(self):
        self.assertEqual(DacByteBus.bit_count(3), 3 * BITS_PER_BYTE)


if __name__ == "__main__":
    unittest.main()
