import unittest

from ui_imports import public_ui_command


class UiImportReferenceTests(unittest.TestCase):
    def test_public_ordinal_cross_check(self) -> None:
        self.assertEqual(public_ui_command(15), "UI_SCROLLSTRING_EXT")
        self.assertEqual(public_ui_command(17), "UI_GET_DC_STATUS")

    def test_unknown_selector_remains_unresolved(self) -> None:
        self.assertIsNone(public_ui_command(3))


if __name__ == "__main__":
    unittest.main()
