import unittest

from usb_audio_stream import UsbAudioStream
from usb_ring import RING_CAPACITY


class UsbAudioStreamTests(unittest.TestCase):
    def test_submit_and_read_preserve_exact_packet_bytes(self) -> None:
        stream = UsbAudioStream()
        packet = bytes(range(200))

        self.assertEqual(stream.submit_packet(packet), 50)
        self.assertEqual(stream.available, 200)
        self.assertEqual(stream.read(200), packet)
        self.assertEqual(stream.available, 0)

    def test_packet_submission_wraps_at_ring_end(self) -> None:
        stream = UsbAudioStream(write_offset=RING_CAPACITY - 40)
        packet = bytes(range(200))

        stream.submit_packet(packet)

        self.assertEqual(stream.read(200), packet)
        self.assertEqual(stream.ring.read_offset, 0)

    def test_short_read_does_not_consume_queued_audio(self) -> None:
        stream = UsbAudioStream()
        packet = bytes(200)
        stream.submit_packet(packet)

        self.assertEqual(stream.read(201), b"")
        self.assertEqual(stream.available, 200)
        self.assertEqual(stream.read(200), packet)

    def test_full_ring_rejects_the_next_packet(self) -> None:
        stream = UsbAudioStream()
        packet = bytes(200)
        for _ in range(RING_CAPACITY // len(packet)):
            stream.submit_packet(packet)

        with self.assertRaises(BufferError):
            stream.submit_packet(packet)

    def test_short_complete_frame_packet_is_queued(self) -> None:
        stream = UsbAudioStream()
        packet = bytes(range(196))

        self.assertEqual(stream.submit_packet(packet), 49)
        self.assertEqual(stream.available, 196)
        self.assertEqual(stream.read(196), packet)

    def test_packet_larger_than_descriptor_capacity_is_rejected(self) -> None:
        stream = UsbAudioStream()

        with self.assertRaises(ValueError):
            stream.submit_packet(bytes(204))


if __name__ == "__main__":
    unittest.main()
