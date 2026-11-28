import unittest

from usound_fifo import (
    READ_ALTERNATE_MODE,
    READ_DATA_MODE,
    WRITE_DATA_MODE,
    WordPortTransfer,
)


class UsoundFifoTests(unittest.TestCase):
    def test_data_port_reads_whole_words(self) -> None:
        port = WordPortTransfer(data_reads=[0x11, 0x22, 0x33])
        words = [0, 0, 0]

        self.assertEqual(port.transfer(READ_DATA_MODE, words, 10), 2)
        self.assertEqual(words, [0x11, 0x22, 0])

    def test_gate_one_suppresses_data_port_reads(self) -> None:
        port = WordPortTransfer(data_reads=[0x11], transfer_gate=1)
        words = [0xAA]

        self.assertEqual(port.transfer(READ_DATA_MODE, words, 4), 1)
        self.assertEqual(words, [0xAA])
        self.assertEqual(port.data_reads, [0x11])

    def test_data_port_writes_whole_words(self) -> None:
        port = WordPortTransfer()

        self.assertEqual(port.transfer(WRITE_DATA_MODE, [1, 2, 3], 9), 2)
        self.assertEqual(port.data_writes, [1, 2])

    def test_alternate_port_reads(self) -> None:
        port = WordPortTransfer(alternate_reads=[0x44, 0x55])
        words = [0, 0]

        self.assertEqual(port.transfer(READ_ALTERNATE_MODE, words, 8), 2)
        self.assertEqual(words, [0x44, 0x55])

    def test_zero_byte_transfer_is_noop(self) -> None:
        port = WordPortTransfer(data_reads=[0x11])
        words = [0xAA]

        self.assertEqual(port.transfer(READ_DATA_MODE, words, 0), 0)
        self.assertEqual(words, [0xAA])


if __name__ == "__main__":
    unittest.main()
