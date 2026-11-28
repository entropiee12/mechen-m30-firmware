import unittest

from scan_mips16_imports import find_import_calls, parse_disassembly


class Mips16ImportTests(unittest.TestCase):
    def test_finds_delay_slot_selectors(self) -> None:
        text = """
260e5794: e840       jalr s0
260e5796: 6f0f       li a3,15
260e579e: e840       jalr s0
260e57a0: 6f11       li a3,17
"""
        calls = find_import_calls(parse_disassembly(text))
        self.assertEqual([(call.call_address, call.selector) for call in calls], [
            (0x260E5794, 15),
            (0x260E579E, 17),
        ])

    def test_ignores_non_adjacent_immediate(self) -> None:
        text = """
1000: e840       jalr s0
1002: 6d00       li a1,0
1004: 6f0f       li a3,15
1006: e9c0       jalrc s1
1008: 6f11       li a3,17
"""
        calls = find_import_calls(parse_disassembly(text))
        self.assertEqual([(call.call_address, call.selector) for call in calls], [(0x1006, 17)])

    def test_filters_requested_selectors(self) -> None:
        text = """
1000: e840       jalr s0
1002: 6f01       li a3,1
1004: e840       jalr s0
1006: 6f0f       li a3,15
"""
        calls = find_import_calls(parse_disassembly(text), selectors={15})
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0].selector, 15)


if __name__ == "__main__":
    unittest.main()
