import unittest
from pathlib import Path

from usound_dac_link_probe import verify


ROOT = Path(__file__).resolve().parents[2]


class UsoundDacLinkProbeTests(unittest.TestCase):
    def test_local_corpora_keep_usb_audio_separate_from_dac_driver(self) -> None:
        for path in sorted((ROOT / "analysis").glob("*/fw/usound.drv")):
            with self.subTest(path=path.parent.parent.name):
                verify(path)


if __name__ == "__main__":
    unittest.main()
