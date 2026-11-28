import unittest

from decoder_output import (
    DECODER_SETUP_COMMAND,
    REJECT_STATUS,
    DecoderWriteBoundary,
)


class DecoderWriteBoundaryTests(unittest.TestCase):
    def test_setup_command_is_the_recovered_command(self) -> None:
        self.assertEqual(DECODER_SETUP_COMMAND, 0x2A)

    def test_unconfigured_callback_rejects_without_consuming_payload(self) -> None:
        boundary = DecoderWriteBoundary()

        result = boundary.write(bytes(200))

        self.assertEqual(result.status, REJECT_STATUS)
        self.assertEqual(result.accepted_bytes, 0)
        self.assertEqual(result.reason, "unconfigured-callback")

    def test_community_rejecting_callback_keeps_same_contract(self) -> None:
        boundary = DecoderWriteBoundary()
        boundary.configure_rejecting_callback()

        result = boundary.write(b"PCM")

        self.assertEqual(result.status, REJECT_STATUS)
        self.assertEqual(result.accepted_bytes, 0)
        self.assertEqual(result.reason, "rejecting-callback")

    def test_unknown_callback_is_not_silently_executed(self) -> None:
        boundary = DecoderWriteBoundary(callback_pointer=0x6015110E)

        with self.assertRaises(ValueError):
            boundary.write(b"PCM")


if __name__ == "__main__":
    unittest.main()
