from pathlib import Path
import unittest

from drvui_clear_call_probe import (
    CALL_OFFSET,
    MODULE_10_STUB_OFFSET,
    POOL_OFFSET,
    inspect_bytes,
    verify,
)


ROOT = Path(__file__).resolve().parents[2]


class DrvUiClearCallProbeTests(unittest.TestCase):
    def test_community_and_stock_call_envelopes_match(self) -> None:
        community = verify(ROOT / "analysis/community/fw/drv_ui.drv")
        stock = verify(ROOT / "analysis/stock/fw/drv_ui.drv")

        self.assertEqual(community, stock)
        self.assertEqual(community.destination, 0x9FC0BD2C)
        self.assertEqual(community.target, 0xBFC09C01)
        self.assertEqual(community.value, 0)
        self.assertEqual(community.length, 0x7C)
        self.assertEqual(community.operation, 1)

    def test_changed_call_instruction_is_rejected(self) -> None:
        source = bytearray((ROOT / "analysis/community/fw/drv_ui.drv").read_bytes())
        source[CALL_OFFSET + 1] ^= 0x01

        with self.assertRaises(AssertionError):
            inspect_bytes(bytes(source))

    def test_changed_literal_pool_is_rejected(self) -> None:
        source = bytearray((ROOT / "analysis/community/fw/drv_ui.drv").read_bytes())
        source[POOL_OFFSET] ^= 0x01

        with self.assertRaises(AssertionError):
            inspect_bytes(bytes(source))

    def test_changed_module_stub_is_rejected(self) -> None:
        source = bytearray((ROOT / "analysis/community/fw/drv_ui.drv").read_bytes())
        source[MODULE_10_STUB_OFFSET] ^= 0x01

        with self.assertRaises(AssertionError):
            inspect_bytes(bytes(source))


if __name__ == "__main__":
    unittest.main()
