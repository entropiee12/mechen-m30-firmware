from pathlib import Path
import unittest

from usound_binary_probe import verify


ROOT = Path(__file__).resolve().parents[2]


class UsoundBinaryProbeTests(unittest.TestCase):
    def test_extracted_driver_has_usb_audio_descriptor_contract(self) -> None:
        verify(ROOT / "analysis/community/fw/usound.drv")


if __name__ == "__main__":
    unittest.main()
