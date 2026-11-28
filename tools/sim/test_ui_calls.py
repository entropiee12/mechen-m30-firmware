import unittest

from ui_calls import classify


class UiCallTests(unittest.TestCase):
    def test_a17_forwarder_is_a_conservative_text_provider_candidate(self):
        target = classify(0x164490A1)
        self.assertIsNotNone(target)
        assert target is not None
        self.assertEqual(target.candidates, ("a17_text_provider",))
        self.assertEqual(target.bank.bank_type, "A")
        self.assertEqual(target.bank.bank_index, 17)
        self.assertEqual(target.bank.payload_offset, 0xA1)
        self.assertEqual(target.confidence, "low")
        self.assertEqual(target.entry_evidence, "external-provider-or-missing-bank")
        self.assertEqual(target.dispatch_policy, "lazy-only")

    def test_font_switch_and_measurement_share_a19(self):
        switch = classify(0x164C90ED)
        measure = classify(0x164C9001)
        self.assertIsNotNone(switch)
        self.assertIsNotNone(measure)
        self.assertEqual(switch.candidates, ("fix_switch_fontlib",))
        self.assertEqual(measure.candidates, ("fixf_get_string_length",))
        self.assertEqual(switch.bank.bank_type, "A")
        self.assertEqual(switch.bank.bank_index, 19)
        self.assertEqual(measure.bank.bank_index, 19)
        self.assertEqual(switch.dispatch_policy, "lazy-only")
        self.assertEqual(measure.entry_evidence, "external-provider-or-missing-bank")

    def test_b2_preprocessing_order(self):
        first = classify(0x26089471)
        second = classify(0x260894E9)
        self.assertEqual(first.candidates, ("arabic_uni_join",))
        self.assertEqual(second.candidates, ("arabic_ascii_turn",))
        self.assertEqual(first.bank.bank_index, 2)
        self.assertEqual(second.bank.bank_index, 2)
        self.assertEqual(first.bank.payload_offset, 0x471)
        self.assertEqual(second.bank.payload_offset, 0x4E9)
        self.assertEqual(first.bank.entry_offset, 0x470)
        self.assertEqual(second.bank.entry_offset, 0x4E8)
        self.assertEqual(first.entry_evidence, "validated-local-code-at-aligned-entry")
        self.assertEqual(first.dispatch_policy, "lazy-load-then-call")
        self.assertEqual(second.entry_evidence, "local-data-or-unresolved-provider")
        self.assertEqual(second.dispatch_policy, "never-direct-call")

    def test_b16_sdimage_renderer(self):
        call = classify(0x26409401)
        self.assertEqual(call.candidates, ("put_string_line_sdimage",))
        self.assertEqual(call.bank.bank_type, "B")
        self.assertEqual(call.bank.bank_index, 16)
        self.assertEqual(call.bank.payload_offset, 0x401)
        self.assertEqual(call.bank.entry_offset, 0x400)
        self.assertEqual(call.confidence, "high")
        self.assertEqual(call.dispatch_policy, "lazy-only")

    def test_b3_trailing_table_candidates_are_validated_lazy_entries(self):
        b04 = classify(0x26109751)
        b07 = classify(0x261C9694)
        b08 = classify(0x26209401)
        self.assertEqual(b04.candidates, ("m30_b04_text_renderer",))
        self.assertEqual(b07.candidates, ("m30_b07_lookup_dispatch",))
        self.assertEqual(b08.candidates, ("m30_b08_descriptor_renderer",))
        self.assertEqual((b04.bank.bank_type, b04.bank.bank_index), ("B", 4))
        self.assertEqual((b07.bank.bank_type, b07.bank.bank_index), ("B", 7))
        self.assertEqual((b08.bank.bank_type, b08.bank.bank_index), ("B", 8))
        self.assertEqual(b04.bank.entry_offset, 0x750)
        self.assertEqual(b07.bank.entry_offset, 0x694)
        self.assertEqual(b08.bank.entry_offset, 0x400)
        for call in (b04, b07, b08):
            self.assertEqual(call.entry_evidence, "validated-local-code-at-aligned-entry")
            self.assertEqual(call.dispatch_policy, "lazy-load-then-call")

    def test_unknown_pointer_is_not_guessed(self):
        self.assertIsNone(classify(0x260E4FF4))


if __name__ == "__main__":
    unittest.main()
