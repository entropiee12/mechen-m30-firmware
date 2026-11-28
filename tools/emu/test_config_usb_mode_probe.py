from pathlib import Path
import unittest

from config_usb_mode_probe import verify


ROOT = Path(__file__).resolve().parents[2]


class ConfigUsbModeProbeTests(unittest.TestCase):
    def test_usb_mode_config_record_is_unchanged(self) -> None:
        verify(ROOT / "analysis/community/fw/config.bin")


if __name__ == "__main__":
    unittest.main()
