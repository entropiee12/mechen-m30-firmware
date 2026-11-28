import unittest

from usound_format import copy_bytes, expand_three_to_four, ring_write_size


class UsoundFormatTests(unittest.TestCase):
    def test_direct_copy(self) -> None:
        self.assertEqual(copy_bytes(b"abcdef", 4), b"abcd")

    def test_three_to_four_expansion(self) -> None:
        self.assertEqual(expand_three_to_four(bytes((1, 2, 3, 4, 5, 6))), bytes((0, 1, 2, 3, 0, 4, 5, 6)))

    def test_expansion_drops_incomplete_tail_like_firmware_loop(self) -> None:
        self.assertEqual(expand_three_to_four(bytes((1, 2, 3, 4)), 4), bytes((0, 1, 2, 3)))

    def test_size_arithmetic(self) -> None:
        self.assertEqual(ring_write_size(6, expanded=True), 8)
        self.assertEqual(ring_write_size(6, expanded=False), 6)


if __name__ == "__main__":
    unittest.main()
