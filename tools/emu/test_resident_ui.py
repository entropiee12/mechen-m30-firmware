import unittest

from resident_ui import classify


class ResidentUiTests(unittest.TestCase):
    def test_odd_mips16_pointers_resolve_to_verified_targets(self) -> None:
        noop = classify(0xBFC09CE1)
        setter = classify(0xBFC0A8BD)
        self.assertIsNotNone(noop)
        self.assertIsNotNone(setter)
        assert noop is not None
        assert setter is not None
        self.assertEqual(noop.name, "ui_noop")
        self.assertEqual(setter.effects, ("write-resident-state-plus-0x10",))

    def test_syscall_wrapper_keeps_conservative_effect_labels(self) -> None:
        target = classify(0xBFC09C01)
        self.assertIsNotNone(target)
        assert target is not None
        self.assertEqual(target.name, "ui_syscall_wrapper")
        self.assertEqual(target.effects, ("trap", "write-status-byte-2"))

    def test_b3_odd_pointer_resolves_to_second_wrapper_entry(self) -> None:
        target = classify(0xBFC09C05)
        self.assertIsNotNone(target)
        assert target is not None
        self.assertEqual(target.pointer, 0xBFC09C04)
        self.assertEqual(target.name, "ui_syscall_wrapper_alt_entry")
        self.assertEqual(target.effects, ("trap", "write-status-byte-2"))

    def test_unobserved_resident_pointer_is_not_guessed(self) -> None:
        self.assertIsNone(classify(0xBFC0A900))


if __name__ == "__main__":
    unittest.main()
