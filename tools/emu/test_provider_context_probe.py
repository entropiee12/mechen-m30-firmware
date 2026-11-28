import tempfile
import unittest
from pathlib import Path

from provider_context_probe import verify_root
from provider_scan import OBSERVED_POINTERS


class ProviderContextProbeTests(unittest.TestCase):
    def test_container_windows_match_driver_consumer(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "fw").mkdir()
            (root / "afi").mkdir()
            driver = bytearray(0x100)
            pointer = next(iter(OBSERVED_POINTERS))
            driver[0x40:0x44] = pointer.to_bytes(4, "little")
            (root / "fw/drv_ui.drv").write_bytes(driver)
            container = bytearray(0x100)
            container[0x60:0x64] = driver[0x40:0x44]
            container[0x40:0x60] = driver[0x20:0x40]
            container[0x64:0xa4] = driver[0x44:0x84]
            (root / "afi/FWIMAGE.FW").write_bytes(container)

            summary = verify_root(root)

        self.assertEqual(summary.occurrences, 1)
        self.assertEqual(summary.unmatched, ())

    def test_unrelated_pointer_hit_is_not_a_provider_match(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "fw").mkdir()
            (root / "afi").mkdir()
            pointer = next(iter(OBSERVED_POINTERS))
            (root / "fw/drv_ui.drv").write_bytes(b"driver" * 64)
            container = bytearray(0x100)
            container[0x60:0x64] = pointer.to_bytes(4, "little")
            (root / "afi/FWIMAGE.FW").write_bytes(container)

            summary = verify_root(root)

        self.assertEqual(summary.occurrences, 1)
        self.assertEqual(len(summary.unmatched), 1)


if __name__ == "__main__":
    unittest.main()
