from __future__ import annotations

import unittest

from usound_driver import resolve_operation_candidate


class UsoundDriverOperationTableTests(unittest.TestCase):
    def test_operation_three_resolves_to_parameter_setter_candidate(self) -> None:
        operation_table = (
            0xBFC3154D,
            0xBFC315F9,
            0xBFC31615,
            0xBFC31629,
            0xBFC31655,
            0xBFC31741,
            0xBFC31755,
            0xBFC318B9,
        )

        candidate = resolve_operation_candidate(operation_table, 3)

        self.assertIsNotNone(candidate)
        self.assertEqual(candidate.raw_pointer, 0xBFC31629)
        self.assertEqual(candidate.code_address, 0xBFC31628)
        self.assertEqual(candidate.instruction_set, "MIPS16")
        self.assertEqual(
            candidate.dispatch_basis,
            "cross-driver table[op] inference",
        )

    def test_zero_and_out_of_range_operations_fail_closed(self) -> None:
        operation_table = (0xBFC3154D, 0, 0xBFC31615, 0xBFC31629, 0, 0, 0, 0)

        self.assertIsNone(resolve_operation_candidate(operation_table, 1))
        self.assertIsNone(resolve_operation_candidate(operation_table, -1))
        self.assertIsNone(resolve_operation_candidate(operation_table, 8))

    def test_table_size_must_match_observed_capacity(self) -> None:
        with self.assertRaises(ValueError):
            resolve_operation_candidate((0xBFC31629,), 0)


if __name__ == "__main__":
    unittest.main()
