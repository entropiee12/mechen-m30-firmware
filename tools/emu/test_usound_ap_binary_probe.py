from pathlib import Path
import unittest

from usound_ap_binary_probe import verify


ROOT = Path(__file__).resolve().parents[2]


class UsoundApBinaryProbeTests(unittest.TestCase):
    def test_driver_and_resources_are_packaged_in_expected_bank(self) -> None:
        verify(ROOT / "analysis/community/fw/usound.ap")


if __name__ == "__main__":
    unittest.main()
