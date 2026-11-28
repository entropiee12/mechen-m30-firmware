import unittest

from bankmap import decode


class BankAddressTests(unittest.TestCase):
    def test_b3_marquee_lead_in(self):
        address = decode(0x260E4FF4)
        self.assertEqual(address.bank_type, "B")
        self.assertEqual(address.bank_index, 3)
        self.assertEqual(address.classification, "overlay-lead-in")
        self.assertEqual(address.window_start, 0x260E5000)

    def test_overlay_payload_is_distinct_from_lead_in(self):
        address = decode(0x260E5000)
        self.assertTrue(address.is_overlay_window)
        self.assertEqual(address.classification, "overlay")

    def test_low_offset_bank_entry_does_not_look_resident(self):
        address = decode(0x26089471)
        self.assertEqual(address.bank_type, "B")
        self.assertEqual(address.bank_index, 2)
        self.assertEqual(address.classification, "bank-stub-or-resident")
        self.assertFalse(address.is_overlay_window)
        self.assertEqual(address.payload_offset, 0x471)
        self.assertEqual(address.entry_offset, 0x470)

    def test_far_entry_candidates_preserve_bank_window(self):
        address = decode(0x26409401)
        self.assertEqual(address.bank_index, 16)
        self.assertEqual(address.window_start, 0x26425000)
        self.assertEqual(address.payload_offset, 0x401)
        self.assertEqual(address.entry_offset, 0x400)
        self.assertEqual(address.candidate_runtime_entry, 0x26425400)

    def test_b3_trailing_table_candidates_map_to_public_runtime_windows(self):
        self.assertEqual(decode(0x26109751).candidate_runtime_entry, 0x26125750)
        self.assertEqual(decode(0x261C9694).candidate_runtime_entry, 0x261E5694)
        self.assertEqual(decode(0x26209401).candidate_runtime_entry, 0x26225400)

    def test_non_overlay_pointer_is_external(self):
        address = decode(0xBFC09C01)
        self.assertEqual(address.classification, "resident-or-external")
        self.assertIsNone(address.bank_index)
        self.assertIsNone(address.candidate_runtime_entry)


if __name__ == "__main__":
    unittest.main()
